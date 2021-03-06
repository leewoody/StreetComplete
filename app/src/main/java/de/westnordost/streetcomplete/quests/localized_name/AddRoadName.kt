package de.westnordost.streetcomplete.quests.localized_name

import de.westnordost.osmapi.map.data.BoundingBox
import de.westnordost.osmapi.map.data.Element
import de.westnordost.streetcomplete.R
import de.westnordost.streetcomplete.data.osm.OsmElementQuestType
import de.westnordost.streetcomplete.data.osm.changes.StringMapChangesBuilder
import de.westnordost.streetcomplete.data.osm.download.MapDataWithGeometryHandler
import de.westnordost.streetcomplete.data.osm.download.OverpassMapDataDao
import de.westnordost.streetcomplete.data.osm.tql.FiltersParser
import de.westnordost.streetcomplete.data.osm.tql.OverpassQLUtil
import de.westnordost.streetcomplete.quests.localized_name.data.PutRoadNameSuggestionsHandler
import de.westnordost.streetcomplete.quests.localized_name.data.RoadNameSuggestionsDao

class AddRoadName(
    private val overpassServer: OverpassMapDataDao,
    private val roadNameSuggestionsDao: RoadNameSuggestionsDao,
    private val putRoadNameSuggestionsHandler: PutRoadNameSuggestionsHandler
) : OsmElementQuestType<RoadNameAnswer> {

    override val commitMessage = "Determine road names and types"
    override val icon = R.drawable.ic_quest_street_name
    override val hasMarkersAtEnds = true

    override fun getTitle(tags: Map<String, String>) =
        if (tags["highway"] == "pedestrian")
            R.string.quest_streetName_pedestrian_title
        else
            R.string.quest_streetName_title

    override fun isApplicableTo(element: Element) = ROADS_WITHOUT_NAMES_TFE.matches(element)

    override fun download(bbox: BoundingBox, handler: MapDataWithGeometryHandler): Boolean {
        return overpassServer.getAndHandleQuota(getOverpassQuery(bbox), handler)
            && overpassServer.getAndHandleQuota(getStreetNameSuggestionsOverpassQuery(bbox),putRoadNameSuggestionsHandler)
    }

    /** returns overpass query string for creating the quests */
    private fun getOverpassQuery(bbox: BoundingBox) =
        OverpassQLUtil.getGlobalOverpassBBox(bbox) +
        ROADS_WITHOUT_NAMES + "; " +
        OverpassQLUtil.getQuestPrintStatement()

    /** return overpass query string to get roads with names near roads that don't have names */
    private fun getStreetNameSuggestionsOverpassQuery(bbox: BoundingBox) =
        OverpassQLUtil.getGlobalOverpassBBox(bbox) +
        ROADS_WITHOUT_NAMES + " -> .without_names;" +
        ROADS_WITH_NAMES + " -> .with_names;" +
        "way.with_names(around.without_names:" +
        MAX_DIST_FOR_ROAD_NAME_SUGGESTION + ");" +
        "out body geom;"

    override fun createForm() = AddRoadNameForm()

    override fun applyAnswerTo(answer: RoadNameAnswer, changes: StringMapChangesBuilder) {
        when(answer) {
            is NoRoadName        -> changes.add("noname", "yes")
            is RoadIsServiceRoad -> changes.modify("highway", "service")
            is RoadIsTrack       -> changes.modify("highway", "track")
            is RoadIsLinkRoad    -> {
                val prevValue = changes.getPreviousValue("highway")
                if (prevValue.matches("primary|secondary|tertiary".toRegex())) {
                    changes.modify("highway", prevValue + "_link")
                }
            }
            is RoadName -> {
                for ((languageCode, name) in answer.localizedNames) {
                    if (languageCode.isEmpty()) {
                        changes.addOrModify("name", name)
                    } else {
                        changes.addOrModify("name:$languageCode", name)
                    }
                }
                // these params are passed from the form only to update the road name suggestions so that
                // newly input street names turn up in the suggestions as well
                val points = answer.wayGeometry.polylines?.getOrNull(0)
                if (points != null) {
                    val roadNameByLanguage = answer.localizedNames.associate { it.languageCode to it.name }
                    roadNameSuggestionsDao.putRoad( answer.wayId, roadNameByLanguage, points)
                }
            }
        }
    }

    companion object {
        const val MAX_DIST_FOR_ROAD_NAME_SUGGESTION = 30.0 //m

        private const val ROADS =
            "primary|secondary|tertiary|unclassified|residential|living_street|pedestrian"
        private const val ROADS_WITH_NAMES = "way[highway~\"^($ROADS)$\"][name]"
        private const val ROADS_WITHOUT_NAMES =
            "way[highway~\"^($ROADS)$\"][!name][!ref][noname != yes][!junction][area != yes]"
        // this must be the same as above but in tag filter expression syntax
        private val ROADS_WITHOUT_NAMES_TFE by lazy { FiltersParser().parse(
            "ways with highway~$ROADS and !name and !ref and noname != yes and !junction and area != yes"
        )}
    }
}
