plugins {
    kotlin("android")
}

version = 1 // Tăng số này mỗi khi bạn cập nhật plugin

cloudstream {
    language = "vi" // hoặc "ru" tùy theo ngôn ngữ chính bạn muốn
    description = "Xem video trực tiếp từ ok.ru trên CloudStream."
    authors = listOf("TênCủaBạn") // Thay bằng tên của bạn
    status = 1 // 1 = hoạt động bình thường
    tvTypes = listOf("Movie", "TvSeries")
    requiresResources = false
    iconUrl = "https://ok.ru/favicon.ico"
}
