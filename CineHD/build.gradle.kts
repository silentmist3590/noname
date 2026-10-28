// Version của plugin (dùng số nguyên Int)
version = 1

android {
    namespace = "com.lagradost.cinehd" // hoặc "lagradost.cinehd" tùy package bạn chọn
}

cloudstream {
    // Thông tin metadata hiển thị trong ứng dụng CloudStream 3
    description = "CineHD - Stream Movies & Series from cinehd.vc (VidRock GCM, Nxsha Nitro, VidLove)"
    authors = listOf("CineHD")
    status = 1 // 1: Hoạt động (Ok), 0: Tạm ngưng (Down), 2: Chậm (Slow), 3: Thử nghiệm (Beta)
    tvTypes = listOf("Movie", "TvSeries")
    language = "en"
}
