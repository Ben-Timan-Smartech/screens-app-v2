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

    enum class Region { USA, UK, EU }

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
