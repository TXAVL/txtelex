package com.customkey.vietime.tools

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.customkey.vietime.ime.ImePrefs

/** Tính năng thuộc gói TxTelex Plus. */
enum class PlusFeature(val title: String, val detail: String) {
    WALLPAPER("Ảnh nền riêng", "Đặt ảnh của bạn làm nền bàn phím, chỉnh mờ và độ tối."),
    CUSTOM_SOUND("Âm phím riêng", "Nạp file âm thanh của bạn làm tiếng gõ phím."),
    ADD_TONES("Thêm dấu cả câu", "Gõ không dấu cả câu, một chạm thêm dấu."),
    CLIPBOARD_PRO("Clipboard nâng cao", "Ghim không giới hạn, nút dán nhanh OTP / link / STK."),
    TRANSLATE("Dịch trên máy", "Dịch Việt ⇄ Anh ngay trên máy, không gửi chữ đi đâu."),
}

/**
 * Cổng TxTelex Plus. [PAYWALL_ENABLED] = false ⇒ MỌI tính năng mở cho mọi người (mặc định hiện
 * tại). Bật true khi đã tạo sản phẩm [PRODUCT_PLUS] trên Play Console.
 */
object PlusGate {
    const val PAYWALL_ENABLED = false
    const val PRODUCT_PLUS = "txtelex_plus"
    const val KEY_PURCHASED = "plus.purchased"

    fun unlocked(ctx: Context): Boolean = !PAYWALL_ENABLED || ImePrefs.of(ctx).getBoolean(KEY_PURCHASED, false)

    fun allows(ctx: Context, @Suppress("UNUSED_PARAMETER") f: PlusFeature): Boolean = unlocked(ctx)
}

/**
 * Google Play Billing cho Plus (mua một lần, acknowledge, không consume). Chỉ kết nối khi
 * [PlusGate.PAYWALL_ENABLED]; kết quả ghi cờ [PlusGate.KEY_PURCHASED] cho bàn phím đọc.
 */
class BillingRepository(context: Context, private val onState: (purchased: Boolean, price: String?) -> Unit) {
    private val ctx = context.applicationContext
    private var details: ProductDetails? = null

    private val client: BillingClient = BillingClient.newBuilder(ctx)
        .setListener { result, purchases -> if (result.responseCode == BillingClient.BillingResponseCode.OK) handle(purchases.orEmpty()) }
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .build()

    fun connect() {
        if (!PlusGate.PAYWALL_ENABLED) { onState(true, null); return }
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(r: BillingResult) {
                if (r.responseCode != BillingClient.BillingResponseCode.OK) return
                refreshPurchases()
                loadDetails()
            }
            override fun onBillingServiceDisconnected() {}
        })
    }

    private fun refreshPurchases() {
        client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()) { r, list ->
            if (r.responseCode == BillingClient.BillingResponseCode.OK) handle(list)
        }
    }

    private fun loadDetails() {
        val p = QueryProductDetailsParams.Product.newBuilder().setProductId(PlusGate.PRODUCT_PLUS)
            .setProductType(BillingClient.ProductType.INAPP).build()
        client.queryProductDetailsAsync(QueryProductDetailsParams.newBuilder().setProductList(listOf(p)).build()) { _, result ->
            details = result.productDetailsList.firstOrNull()
            onState(purchased(), details?.oneTimePurchaseOfferDetails?.formattedPrice)
        }
    }

    fun purchase(activity: Activity): Boolean {
        val d = details ?: return false
        val params = BillingFlowParams.newBuilder().setProductDetailsParamsList(
            listOf(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(d).build()),
        ).build()
        return client.launchBillingFlow(activity, params).responseCode == BillingClient.BillingResponseCode.OK
    }

    private fun handle(purchases: List<Purchase>) {
        var owned = false
        for (p in purchases) {
            if (PlusGate.PRODUCT_PLUS !in p.products || p.purchaseState != Purchase.PurchaseState.PURCHASED) continue
            owned = true
            if (!p.isAcknowledged) client.acknowledgePurchase(
                AcknowledgePurchaseParams.newBuilder().setPurchaseToken(p.purchaseToken).build(),
            ) {}
        }
        ImePrefs.of(ctx).edit().putBoolean(PlusGate.KEY_PURCHASED, owned).apply()
        onState(owned, details?.oneTimePurchaseOfferDetails?.formattedPrice)
    }

    private fun purchased() = ImePrefs.of(ctx).getBoolean(PlusGate.KEY_PURCHASED, false)

    fun release() { runCatching { client.endConnection() } }
}
