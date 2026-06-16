package com.smartech.screens.data

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

    data class Store(
        val id: String,
        val name: String,
        val address: String,
        val cityCode: String,
    )

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

    val stores: List<Store> = listOf(
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
        // Test for dev/QA fixtures. A CMS-side "add new store" flow
        // is still pending — for now this hardcoded list is the
        // source of truth and additions require an APK update.
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

    /** Region the given city sits in. Used to auto-fill the region row. */
    fun regionOfCity(cityCode: String?): Region? =
        cityCode?.let { code -> cities.firstOrNull { it.code == code }?.region }
}
