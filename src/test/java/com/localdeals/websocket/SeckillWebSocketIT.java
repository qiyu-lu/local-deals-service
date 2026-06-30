package com.localdeals.websocket;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@ActiveProfiles("test")
class SeckillWebSocketIT {

    @Autowired
    private SeckillWebSocketHandler webSocketHandler;

    @Autowired
    private WebSocketNotifier webSocketNotifier;

    @Test
    void notify_sendsMessageToRegisteredSession() throws Exception {
        Long userId = 12345L;

        // Create mock session
        WebSocketSession mockSession = Mockito.mock(WebSocketSession.class);
        Mockito.when(mockSession.isOpen()).thenReturn(true);

        // Register session directly into handler
        webSocketHandler.getSessionsForTest().put(userId, mockSession);

        try {
            // Send notification via Redis pub/sub; RedisMessageListenerContainer should
            // forward it to webSocketHandler.sendToUser(...) on this same JVM.
            webSocketNotifier.notify(userId, true, 99L, 10L);

            // Wait for Redis pub/sub delivery
            await()
                    .atMost(3, TimeUnit.SECONDS)
                    .untilAsserted(() ->
                            Mockito.verify(mockSession, Mockito.atLeastOnce())
                                    .sendMessage(ArgumentMatchers.any()));

            // Capture the message and verify content
            ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
            Mockito.verify(mockSession, Mockito.atLeastOnce()).sendMessage(captor.capture());
            String payload = captor.getValue().getPayload();
            assertThat(payload).contains("\"success\":true");
            assertThat(payload).contains("\"orderId\":99");
            assertThat(payload).contains("\"voucherId\":10");
            assertThat(payload).contains("SECKILL_RESULT");
        } finally {
            webSocketHandler.getSessionsForTest().remove(userId);
        }
    }
}
