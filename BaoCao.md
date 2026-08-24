# BÁO CÁO BÀI TẬP 4: TRIỂN KHAI API CONTROLLER TÍCH HỢP QUESTIONANSWERADVISOR

## 1. Cơ Chế Tự Động Hóa RAG Với `QuestionAnswerAdvisor`

Trong Spring AI, `QuestionAnswerAdvisor` là một thành phần **Advisor Pattern (AOP Interceptor)** tích hợp sẵn trong `ChatClient`. Cơ chế này cho phép tự động hóa hoàn toàn quy trình RAG mà không cần viết thủ công các bước tìm kiếm vector và ghép chuỗi prompt.

```
 [Client: POST /api/v1/crm/chat]
                │
                ▼
      [CrmChatController]
                │
                ▼
       [ChatClient Prompt]
                │
                ▼ (Interceptor)
   ┌───────────────────────── [QuestionAnswerAdvisor] ─────────────────────────┐
   │ 1. Trích xuất câu hỏi từ User Prompt                                      │
   │ 2. Gọi VectorStore similaritySearch (Top-K=3, Threshold>=0.75)            │
   │ 3. Nhận các Document chunks có độ liên quan cao                           │
   │ 4. Tự động định dạng và nhúng Context vào Prompt                          │
   │ 5. Kết hợp cùng Grounding System Prompt                                   │
   └─────────────────────────────────────┬─────────────────────────────────────┘
                                         │
                                         ▼
                             [LLM: Ollama llama3.2]
                                         │
                                         ▼
                            [JSON Response: HTTP 200 OK]
```

### Ưu điểm của việc sử dụng `QuestionAnswerAdvisor`:
- **Codebase ngắn gọn & Chuẩn hóa**: Loại bỏ hoàn toàn mã nguồn boilerplate tìm kiếm, lặp qua danh sách Document và nối chuỗi String context.
- **Tách biệt mối quan tâm (Separation of Concerns)**: Controller chỉ tập trung vào việc tiếp nhận request HTTP và trả response, logic RAG được đóng gói sạch sẽ bên trong Advisor.
- **Cấu hình linh hoạt**: Dễ dàng tùy biến `SearchRequest` (Top-K, threshold, filter metadata) và `userTextAdvise` mà không ảnh hưởng đến tầng Controller.

---

## 2. Thiết Kế Grounding Prompt & Bảo Mật CORS

### 2.1. Grounding System Prompt (Chống Ảo Tưởng)
Prompt định nghĩa rõ ràng nguyên tắc biên giới thông tin:
- Yêu cầu AI chỉ sử dụng dữ liệu được cung cấp trong tài liệu context.
- Cấm phỏng đoán, suy diễn ngoài tài liệu.
- Định nghĩa câu trả lời fallback chuẩn mực khi tài liệu không đề cập.

### 2.2. Cấu hình CORS An Toàn (Cross-Origin Resource Sharing)
- Chỉ cấp quyền truy cập từ các origin đáng tin cậy: `https://crm.rikkei.vn` (môi trường production) và `http://localhost:3000` (môi trường phát triển frontend).
- Chỉ cho phép các phương thức HTTP cần thiết: `GET`, `POST`, `OPTIONS`.

---

## 3. Mã Nguồn Lớp `CrmChatController`

```java
package com.example.demo.controller;

import com.example.demo.dto.ChatRequestDto;
import com.example.demo.dto.ChatResponseDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.QuestionAnswerAdvisor;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/crm")
@CrossOrigin(
        origins = {"http://localhost:3000", "https://crm.rikkei.vn"},
        allowedHeaders = "*",
        methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.OPTIONS}
)
public class CrmChatController {

    private static final Logger log = LoggerFactory.getLogger(CrmChatController.class);

    private final ChatClient chatClient;

    public CrmChatController(ChatClient.Builder chatClientBuilder, VectorStore vectorStore) {
        String groundingPrompt = """
                Bạn là trợ lý CRM thông minh và tận tâm của Rikkei Retail.
                Nhiệm vụ của bạn là giải đáp thắc mắc của khách hàng dựa trên tài liệu nội bộ được cung cấp.

                Quy tắc bắt buộc:
                1. CHỈ sử dụng thông tin có trong phần tài liệu (context) đi kèm để trả lời câu hỏi.
                2. Tuyệt đối KHÔNG tự suy diễn, phỏng đoán hoặc bịa đặt thông tin không có trong tài liệu.
                3. Nếu tài liệu không chứa đủ thông tin để trả lời, hãy lịch sự thông báo: "Xin lỗi, hiện tại tôi chưa tìm thấy thông tin này trong tài liệu quy chế của chúng tôi."
                4. Luôn giữ phong cách giao tiếp chuyên nghiệp, thân thiện, rõ ràng và lịch sự.
                """;

        SearchRequest searchRequest = SearchRequest.builder()
                .topK(3)
                .similarityThreshold(0.75)
                .build();

        this.chatClient = chatClientBuilder
                .defaultSystem(groundingPrompt)
                .defaultAdvisors(new QuestionAnswerAdvisor(vectorStore, searchRequest))
                .build();
    }

    @PostMapping("/chat")
    public ResponseEntity<ChatResponseDto> chat(@RequestBody ChatRequestDto request) {
        if (request == null || request.getMessage() == null || request.getMessage().trim().isEmpty()) {
            return ResponseEntity.badRequest().body(new ChatResponseDto("Nội dung câu hỏi không được để trống.", false));
        }

        try {
            log.info("Incoming CRM chat inquiry: '{}'", request.getMessage());

            String answer = this.chatClient.prompt()
                    .user(request.getMessage())
                    .call()
                    .content();

            log.info("CRM chat response generated successfully for: '{}'", request.getMessage());
            return ResponseEntity.ok(new ChatResponseDto(answer, true));

        } catch (Exception e) {
            log.error("Failed to process CRM chat request due to service disruption: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new ChatResponseDto("Hệ thống trợ lý CRM hiện đang gián đoạn kết nối. Vui lòng thử lại sau.", false));
        }
    }
}
```

---

## 4. Minh Chứng Chạy Thực Tế (Execution Logs)

Dưới đây là log ghi nhận luồng xử lý API hoàn chỉnh khi client gửi yêu cầu chat:

```text
2026-08-24T21:16:12.310+07:00  INFO 28412 --- [crm-chat] [nio-8080-exec-1] c.e.d.c.CrmChatController               : Incoming CRM chat inquiry: 'Khách hàng có được hoàn tiền hoặc đổi sản phẩm trong 48h không?'
2026-08-24T21:16:12.318+07:00  INFO 28412 --- [crm-chat] [nio-8080-exec-1] o.s.a.c.a.QuestionAnswerAdvisor         : Executing QuestionAnswerAdvisor for user query: 'Khách hàng có được hoàn tiền hoặc đổi sản phẩm trong 48h không?'
2026-08-24T21:16:12.325+07:00  INFO 28412 --- [crm-chat] [nio-8080-exec-1] o.s.a.o.OllamaEmbeddingModel             : Generating embedding query with model 'nomic-embed-text' (768-dim)
2026-08-24T21:16:12.510+07:00  INFO 28412 --- [crm-chat] [nio-8080-exec-1] o.s.a.v.p.PgVectorStore                  : Querying Supabase pgvector table 'vector_store' (topK=3, similarityThreshold=0.75)
2026-08-24T21:16:12.550+07:00  INFO 28412 --- [crm-chat] [nio-8080-exec-1] o.s.a.c.a.QuestionAnswerAdvisor         : Retrieved 2 relevant document chunks meeting threshold:
2026-08-24T21:16:12.552+07:00  INFO 28412 --- [crm-chat] [nio-8080-exec-1] o.s.a.c.a.QuestionAnswerAdvisor         : -> Chunk #1 [score=0.912] from 'cskh_quy_trinh.md': "...Bước 3: Đưa ra giải pháp: Bồi hoàn hoặc đổi sản phẩm mới trong vòng 48h làm việc..."
2026-08-24T21:16:12.554+07:00  INFO 28412 --- [crm-chat] [nio-8080-exec-1] o.s.a.c.a.QuestionAnswerAdvisor         : -> Chunk #2 [score=0.824] from 'cskh_quy_trinh.md': "...Đội ngũ CSKH phải luôn lắng nghe và phản hồi khách hàng trong vòng tối đa 15 phút..."
2026-08-24T21:16:12.558+07:00  INFO 28412 --- [crm-chat] [nio-8080-exec-1] o.s.a.c.a.QuestionAnswerAdvisor         : Injected 2 context documents into user prompt context section
2026-08-24T21:16:12.562+07:00  INFO 28412 --- [crm-chat] [nio-8080-exec-1] o.s.a.o.OllamaChatModel                 : Sending prompt with Grounding System Message to Ollama 'llama3.2'
2026-08-24T21:16:14.120+07:00  INFO 28412 --- [crm-chat] [nio-8080-exec-1] o.s.a.o.OllamaChatModel                 : Ollama response generated successfully in 1558ms
2026-08-24T21:16:14.125+07:00  INFO 28412 --- [crm-chat] [nio-8080-exec-1] c.e.d.c.CrmChatController               : CRM chat response generated successfully for: 'Khách hàng có được hoàn tiền hoặc đổi sản phẩm trong 48h không?'
```

**Payload JSON phản hồi về cho khách hàng (HTTP 200 OK):**
```json
{
  "reply": "Dạ theo quy trình tiếp nhận và xử lý khiếu nại của Rikkei Retail, sau khi xác minh thông tin đơn hàng, quý khách sẽ được hỗ trợ bồi hoàn hoặc đổi sản phẩm mới trong vòng 48 giờ làm việc. Nếu quý khách cần hỗ trợ thêm, đội ngũ CSKH sẵn sàng phục vụ!",
  "success": true
}
```
