package io.github.xw66.knowflowai;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class KnowFlowAiApplicationTests {

    @LocalServerPort
    private int port;

    @Test
    void healthReturnsUpWithoutInternalDetails() throws Exception {
        var response = get("/actuator/health");

        assertThat(response.statusCode()).isEqualTo(200);
        Map<String, Object> body = JsonPath.read(response.body(), "$");
        assertThat(body).containsEntry("status", "UP")
                .containsOnlyKeys("status", "groups");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/env", "/actuator/configprops", "/actuator/beans"})
    void sensitiveEndpointsAreNotExposed(String path) throws Exception {
        assertThat(get(path).statusCode()).isEqualTo(404);
    }

    private HttpResponse<String> get(String path) throws Exception {
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

}
