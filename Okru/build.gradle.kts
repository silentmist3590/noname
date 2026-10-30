plugins {
    kotlin("android")
}

// Version phải là số nguyên dương, tăng mỗi khi cập nhật
version = 5

cloudstream {
    language = "vi"
    description = "Xem video trực tiếp từ ok.ru trên CloudStream (unofficial)."
    authors = listOf("silentmist3590")
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    requiresResources = false
    iconUrl = "https://ok.ru/favicon.ico"
}
