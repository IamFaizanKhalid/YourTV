package com.iamfaizankhalid.YourTV

data class Channel(
    val name: String,
    val youtubeUrl: String,
    val group: String
)

object Channels {
    val all: List<Channel> = listOf(
        Channel("Al Jazeera English", "https://www.youtube.com/@AlJazeeraEnglish", "International"),
        Channel("TRT World", "https://www.youtube.com/@TRTWorld", "International"),
        Channel("CGTN", "https://www.youtube.com/@CGTN", "International"),
        Channel("DW News", "https://www.youtube.com/@DWNews", "International"),
        Channel("CNN", "https://www.youtube.com/@CNN", "International"),
        Channel("Sky News", "https://www.youtube.com/@SkyNews", "International"),
        Channel("France 24 English", "https://www.youtube.com/@France24_en", "International"),
        Channel("Euronews", "https://www.youtube.com/@euronews", "International"),
        Channel("Bloomberg Television", "https://www.youtube.com/@markets", "International"),
        Channel("CNA", "https://www.youtube.com/@channelnewsasia", "International"),
        Channel("WION", "https://www.youtube.com/@WION", "International"),
        Channel("NBC News", "https://www.youtube.com/@NBCNews", "International"),
        Channel("ABC News", "https://www.youtube.com/@ABCNews", "International"),


        Channel("Geo News", "https://www.youtube.com/@GeoNews", "Pakistan"),
        Channel("ARY News", "https://www.youtube.com/@ArynewsTvofficial", "Pakistan"),
        Channel("Dunya News", "https://www.youtube.com/@DunyanewsOfficial", "Pakistan"),
        Channel("Samaa TV", "https://www.youtube.com/@SAMAATV", "Pakistan"),
        Channel("Express News", "https://www.youtube.com/@ExpressNewspkofficial", "Pakistan"),
        Channel("92 News HD", "https://www.youtube.com/@92newshdtv", "Pakistan"),
        Channel("HUM News", "https://www.youtube.com/@humnewspakistan", "Pakistan"),
        Channel("Bol News", "https://www.youtube.com/@BOLNewsofficial", "Pakistan"),
        Channel("Aaj News", "https://www.youtube.com/@aajtvofficial", "Pakistan"),
        Channel("GNN", "https://www.youtube.com/@gnnhdofficial", "Pakistan"),
        Channel("Dawn News", "https://www.youtube.com/@dawnnewspakistan", "Pakistan"),
        Channel("Suno News HD", "https://www.youtube.com/channel/UCB82Zom1MYh8VV4q7wXkfGA", "Pakistan")
    )
}
