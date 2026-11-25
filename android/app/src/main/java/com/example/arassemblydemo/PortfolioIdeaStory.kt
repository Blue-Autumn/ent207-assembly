package com.example.arassemblydemo

internal data class PortfolioIdeaStory(val description: Int, val subjectTag: Int, val detailTag: Int)

internal fun PortfolioIdea.story(): PortfolioIdeaStory = when (this) {
    PortfolioIdea.STOOL -> PortfolioIdeaStory(R.string.idea_stool, R.string.idea_tag_furniture, R.string.idea_tag_cross_rails)
    PortfolioIdea.LAMP -> PortfolioIdeaStory(R.string.idea_lamp, R.string.idea_tag_lighting, R.string.idea_tag_warm_light)
    PortfolioIdea.RECORD_PLAYER -> PortfolioIdeaStory(R.string.idea_recordplayer, R.string.idea_tag_music, R.string.idea_tag_vinyl)
    PortfolioIdea.BRIDGE -> PortfolioIdeaStory(R.string.idea_bridge, R.string.idea_tag_miniature, R.string.idea_tag_footbridge)
    PortfolioIdea.BOOKS -> PortfolioIdeaStory(R.string.idea_bookstand, R.string.idea_tag_reading, R.string.idea_tag_bookends)
    PortfolioIdea.TRAY -> PortfolioIdeaStory(R.string.idea_tray, R.string.idea_tag_storage, R.string.idea_tag_stationery)
    PortfolioIdea.XYLOPHONE -> PortfolioIdeaStory(R.string.idea_xylophone, R.string.idea_tag_music, R.string.idea_tag_tone_bars)
    PortfolioIdea.DRAWERS -> PortfolioIdeaStory(R.string.idea_drawers, R.string.idea_tag_storage, R.string.idea_tag_drawers)
    PortfolioIdea.PLANTS -> PortfolioIdeaStory(R.string.idea_plantstand, R.string.idea_tag_plants, R.string.idea_tag_pot_stand)
    PortfolioIdea.ORGANIZER -> PortfolioIdeaStory(R.string.idea_organizer, R.string.idea_tag_storage, R.string.idea_tag_cubbies)
}
