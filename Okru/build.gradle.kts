plugins {
    kotlin("android")
}

// Version phải là số nguyên dương — tăng mỗi khi cập nhật
version = 3

cloudstream {
    language = "vi"
    description = "Xem video trực tiếp từ ok.ru trên CloudStream."
    authors = listOf("silentmist3590") // Thay bằng tên GitHub của bạn
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    requiresResources = false
    iconUrl = "https://ok.ru/favicon.ico"
}
