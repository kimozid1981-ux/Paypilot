package app.paypilot.server.connectors

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** An invoice as seen on an external platform, normalised for PayPilot. */
@Serializable
data class ExternalInvoice(
    val platform: String,
    val externalId: String,
    val number: String?,
    val customerName: String?,
    val amountDueCents: Long,
    val amountPaidCents: Long,
    val currency: String,
    val status: String,
    val dueEpochSeconds: Long?,
)

@Serializable
data class ConnectorInfo(val id: String, val name: String, val kind: String, val available: Boolean, val note: String)

/**
 * Every financial platform plugs in through this interface: payment providers, e-invoicing
 * approved platforms (plateformes agréées), accounting software, open banking aggregators.
 * Credentials are per business, stored server-side, never in the app.
 */
interface FinancialConnector {
    val info: ConnectorInfo
    fun listOpenInvoices(credentials: Map<String, String>): List<ExternalInvoice>
}

class StripeConnector(private val baseUrl: String = "https://api.stripe.com") : FinancialConnector {
    override val info = ConnectorInfo("stripe", "Stripe", "payments", true, "Factures et paiements Stripe (clé restreinte en lecture seule).")
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val json = Json { ignoreUnknownKeys = true }

    override fun listOpenInvoices(credentials: Map<String, String>): List<ExternalInvoice> {
        val key = credentials["restricted_key"] ?: error("Missing Stripe restricted key")
        require(key.startsWith("rk_")) { "Use a Stripe restricted key (rk_...) with read-only invoice access." }
        val url = "$baseUrl/v1/invoices?status=${URLEncoder.encode("open", Charsets.UTF_8)}&limit=100"
        val request = HttpRequest.newBuilder(URI(url)).header("Authorization", "Bearer $key").timeout(Duration.ofSeconds(20)).GET().build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) { "Stripe returned HTTP ${response.statusCode()}" }
        return parse(response.body())
    }

    fun parse(body: String): List<ExternalInvoice> =
        json.parseToJsonElement(body).jsonObject["data"]!!.jsonArray.map { el ->
            val o = el.jsonObject
            ExternalInvoice(
                platform = "stripe",
                externalId = o["id"]!!.jsonPrimitive.content,
                number = o["number"]?.jsonPrimitive?.contentOrNullSafe(),
                customerName = o["customer_name"]?.jsonPrimitive?.contentOrNullSafe(),
                amountDueCents = o["amount_due"]!!.jsonPrimitive.long,
                amountPaidCents = o["amount_paid"]?.jsonPrimitive?.longOrNull ?: 0,
                currency = o["currency"]!!.jsonPrimitive.content.uppercase(),
                status = o["status"]!!.jsonPrimitive.content,
                dueEpochSeconds = o["due_date"]?.jsonPrimitive?.longOrNull,
            )
        }

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? = if (isString) content else null
}

/** Placeholders shown in the app so owners can ask for them; each needs a partner contract or API access. */
class PlannedConnector(override val info: ConnectorInfo) : FinancialConnector {
    override fun listOpenInvoices(credentials: Map<String, String>): List<ExternalInvoice> =
        throw UnsupportedOperationException("${info.name} n'est pas encore disponible.")
}

class ConnectorRegistry(connectors: List<FinancialConnector>) {
    private val byId = connectors.associateBy { it.info.id }
    fun all(): List<ConnectorInfo> = byId.values.map { it.info }
    fun get(id: String): FinancialConnector? = byId[id]

    companion object {
        fun default() = ConnectorRegistry(
            listOf(
                StripeConnector(),
                PlannedConnector(ConnectorInfo("chorus-pro", "Chorus Pro", "e-invoicing", false, "Factures aux clients publics. Nécessite un compte technique PISTE.")),
                PlannedConnector(ConnectorInfo("pa-generic", "Plateforme agréée", "e-invoicing", false, "Statuts du cycle de vie (déposée, refusée, encaissée) via l'API de votre plateforme.")),
                PlannedConnector(ConnectorInfo("open-banking", "Banque (open banking)", "banking", false, "Détection des paiements reçus. Nécessite un agrégateur agréé (contrat).")),
                PlannedConnector(ConnectorInfo("pennylane", "Pennylane", "accounting", false, "Synchronisation des factures clients.")),
            ),
        )
    }
}
