// use an integer for version numbers
version = 1

cloudstream {
    description = "HDMovie4 - Movies & Web Series"
    authors = listOf("parmod19")

    /**
     * Status int as one of the following:
     * 0: Down, 1: Ok, 2: Slow, 3: Beta only
     **/
    status = 1

    tvTypes = listOf("Movie", "TvSeries")

    requiresResources = false
    language = "hi"

    iconUrl = "https://www.google.com/s2/favicons?domain=hdmovie4.com&sz=%size%"
}

android {
    namespace = "com.hdmovie4"
}
