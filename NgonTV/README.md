# NgonTV — CloudStream provider source

Tên hiển thị: **NgonTV**  
Website chính: `https://ngontv.com`  
Phiên bản source: `24`

## Nội dung

- `src/main/kotlin/com/ngontv/NgonTVProvider.kt`: mã nguồn provider Kotlin, điều chỉnh từ provider bạn gửi.
- `build.gradle.kts`: metadata CloudStream và version.

## Những gì đã điều chỉnh

- Đổi package/lớp/provider sang `com.ngontv.NgonTVProvider` / `NgonTV`.
- Đổi `mainUrl` sang `https://ngontv.com`.
- Tìm link phim từ các anchor có dạng `-f<ID>.html`, giảm phụ thuộc vào CSS cũ.
- Cập nhật nhận diện embed host `grab.ngontv.com` và iframe `embed.streamc.xyz` theo HAR đã cung cấp.
- Thêm dò phụ đề khai báo bằng `<track>` và URL `.vtt`, `.srt`, `.ass` trong HTML.
- Giữ cơ chế phân giải stream nhiều tầng đã có trong mã nguồn ban đầu.

## Giới hạn cần biết

- Đây là **source project**, chưa phải file plugin `.cs3` đã build.
- Môi trường hiện tại không có Android SDK/CloudStream Gradle build setup để xác nhận build end-to-end. Cần build trong extension template/repository CloudStream tương thích.
- HAR đã cung cấp ghi nhận HLS `.m3u8`, nhưng chưa chứng minh có master playlist nhiều độ phân giải. Mã nguồn gốc đang chọn playlist con có bandwidth cao nhất khi gặp master playlist; vì vậy **chưa thể coi là adaptive bitrate hoàn chỉnh**.
- HAR không ghi nhận URL phụ đề `.vtt`/`.srt`; phần dò phụ đề chỉ cung cấp track nếu trang/player thực sự khai báo URL. **Dịch tự động phụ đề chưa được tích hợp**; cần quyết định dùng dịch vụ/API nào và xử lý giới hạn khóa API/quyền riêng tư.
- Cấu trúc HTML và API của website có thể thay đổi; cần thử trên CloudStream với một phim/tập thực tế. Nếu phát lỗi, gửi log `NgonTV` được plugin chép vào clipboard để tiếp tục sửa.

## Build

Đưa `src/main/kotlin/com/ngontv/NgonTVProvider.kt` và `build.gradle.kts` vào một extension project tương thích với phiên bản CloudStream đang dùng, sau đó build theo hướng dẫn của template đó. Không đổi package/lớp khai báo provider khi thêm vào project nếu template yêu cầu đăng ký riêng.
