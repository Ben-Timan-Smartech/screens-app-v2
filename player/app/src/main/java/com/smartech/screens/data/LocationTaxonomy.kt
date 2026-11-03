package com.smartech.screens.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Pickable values for the cascading location dropdowns in the on-tablet
 * Device admin screen. Mirrored on the CMS side in
 * `app/components/data.jsx` (LOCATION_TAXONOMY).
 *
 * Hierarchy: region → city → store → concept → floor → table → screen code.
 * Screen code is free text; everything else is a fixed dropdown.
 */
object LocationTaxonomy {

    enum class Region { USA, UK, EU, GLOBAL }

    data class City(val code: String, val region: Region)

    /** v0.2.15: [kind] is [KIND_RETAIL] or [KIND_OFFICE]. Offices are
     *  added from the CMS (Settings → Locations) and never ask for a
     *  concept or floor. */
    data class Store(
        val id: String,
        val name: String,
        val address: String,
        val cityCode: String,
        val kind: String = KIND_RETAIL,
    ) {
        val isOffice: Boolean get() = kind == KIND_OFFICE
    }

    const val KIND_RETAIL = "retail"
    const val KIND_OFFICE = "office"

    val regions: List<Region> = Region.entries

    val cities: List<City> = listOf(
        City("NYC", Region.USA),
        City("LDN", Region.UK),
        City("BER", Region.EU),
        City("ROM", Region.EU),
        // v0.1.37: GLB hosts the "Events" + "Test" stores and any
        // custom-added store that isn't anchored to a real retail
        // city. Region GLOBAL keeps them out of the cascade dropdowns
        // a regional operator drives until they explicitly pick it.
        City("GLB", Region.GLOBAL),
    )

    // Built-in stores. Custom stores added from the CMS land on the
    // tablet via [setCustomStores] which merges /api/stores at app
    // launch. Built-ins always win on id collision so a misconfigured
    // server entry can't shadow a known retail store.
    private val builtIn: List<Store> = listOf(
        Store(
            id = "tmrw-times-square",
            name = "tm:rw Times Square",
            address = "220W 42nd Street, 10036",
            cityCode = "NYC",
        ),
        Store(
            id = "smartech-selfridges",
            name = "Smartech · Selfridges LDN",
            address = "400 Oxford St, Marylebone, Selfridges, London W1A 1AB",
            cityCode = "LDN",
        ),
        Store(
            id = "smartech-kadewe",
            name = "Smartech · KaDeWe",
            address = "Tauentzienstraße 21–24, 10789 Berlin",
            cityCode = "BER",
        ),
        Store(
            id = "tmrw-rinascente",
            name = "tm:rw · La Rinascente",
            address = "Galleria Alberto Sordi, 00187 Roma",
            cityCode = "ROM",
        ),
        // v0.1.37: ad-hoc stores. Events for pop-ups + trade shows,
        // Test for dev/QA fixtures. v0.1.38 added a CMS-side add-store
        // form which appends to the list at runtime via [setCustomStores].
        Store(
            id = "events",
            name = "Events",
            address = "Pop-up + event installations",
            cityCode = "GLB",
        ),
        Store(
            id = "test",
            name = "Test",
            address = "Development & QA fixtures",
            cityCode = "GLB",
        ),
    )

    // v0.2.15: a StateFlow (was a @Volatile list) so the setup screen
    // redraws when a location added in the CMS arrives mid-setup.
    private val _stores = MutableStateFlow(builtIn)
    val storesFlow: StateFlow<List<Store>> = _stores.asStateFlow()

    /**
     * v0.1.38: replace the dynamic store list with the server's
     * /api/stores response. Called at app launch and while the setup
     * screen is open, from
     * [com.smartech.screens.data.PlayerRepository.refreshStoresFromServer].
     * Built-ins always win on id collision. Returns true if the list changed.
     */
    fun setCustomStores(next: List<Store>): Boolean {
        val builtInIds = builtIn.map { it.id }.toSet()
        val merged = builtIn + next.filter { it.id !in builtInIds }
        if (merged == _stores.value) return false
        _stores.value = merged
        return true
    }

    /** Built-ins first, then any custom stores fetched from the server. */
    val stores: List<Store> get() = _stores.value

    val concepts: List<String> = listOf(
        "Smartech", "Playhouse", "Sanctuary", "Bikeshop",
        "The Track", "7EVN", "Cornershop", "tm:rw Cafe",
    )

    val floors: List<String> = listOf("GF", "MEZ", "TF")

    /** Tables are namespaced by floor — id is "<floor>.<letter>". */
    val tables: List<String> = listOf("GF.A", "MEZ.A", "TF.A", "GF.B")

    // ── Cascade filters ────────────────────────────────────────────

    fun citiesIn(region: Region?): List<City> =
        if (region == null) cities else cities.filter { it.region == region }

    fun storesIn(cityCode: String?): List<Store> =
        if (cityCode == null) stores else stores.filter { it.cityCode == cityCode }

    fun tablesOn(floor: String?): List<String> =
        if (floor == null) tables else tables.filter { it.startsWith("$floor.") }

    fun storeById(id: String?): Store? = id?.let { stores.firstOrNull { s -> s.id == it } }

    /** Cities where multiple in-store concepts (Smartech, Playhouse, etc) coexist. */
    val MULTI_CONCEPT_CITIES: Set<String> = setOf("NYC", "LDN")

    /** Concept is only asked for at retail stores in a multi-concept city.
     *  Offices, Events/Test and single-concept cities (BER, ROM) skip it. */
    fun conceptApplies(cityCode: String?, storeId: String?): Boolean =
        cityCode != null && cityCode in MULTI_CONCEPT_CITIES && storeById(storeId)?.isOffice != true

    /** Floors only exist at the NYC store; offices never use them. */
    fun floorApplies(cityCode: String?, storeId: String?): Boolean =
        cityCode == "NYC" && storeById(storeId)?.isOffice != true

    /** Region the given city sits in. Used to auto-fill the region row. */
    fun regionOfCity(cityCode: String?): Region? =
        cityCode?.let { code -> cities.firstOrNull { it.code == code }?.region }
}
