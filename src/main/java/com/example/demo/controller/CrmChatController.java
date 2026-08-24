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
