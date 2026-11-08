package com.iamfaizankhalid.YourTV

data class Channel(
    val name: String,
    val youtubeUrl: String,
    val group: String,
    val fixedLiveVideoId: String? = null
)

object Channels {
    val all: List<Channel> = listOf(
        Channel(
            "Al Jazeera English",
            "https://www.youtube.com/@AlJazeeraEnglish",
            "International",
            "gCNeDWCI0vo"
        ),
        Channel("TRT World", "https://www.youtube.com/@TRTWorld", "International", "JV5w1MhzcfQ"),
        Channel("CGTN", "https://www.youtube.com/@CGTN", "International", "0i7n3r01L2U"),
        Channel("DW News", "https://www.youtube.com/@DWNews", "International", "LuKwFajn37U"),
        Channel("CNN", "https://www.youtube.com/@CNN", "International","GotlA1KKWoo"),
        Channel("Geo News", "https://www.youtube.com/@GeoNews", "Pakistan", "t3fvgmDDmdc"),
        Channel("ARY News", "https://www.youtube.com/@ArynewsTvofficial", "Pakistan","5FW9ZVMR_7M"),
        Channel("Dunya News", "https://www.youtube.com/@DunyanewsOfficial", "Pakistan","e2XVSUYh4S0"),
        Channel("Samaa TV", "https://www.youtube.com/@SAMAATV", "Pakistan","MLDQM0QL_-4"),
        Channel("Express News", "https://www.youtube.com/@ExpressNewspkofficial", "Pakistan", "KNvaWHgOBMw")
    )
}
