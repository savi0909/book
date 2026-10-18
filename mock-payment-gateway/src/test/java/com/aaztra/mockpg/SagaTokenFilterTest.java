package com.aaztra.mockpg;

import com.aaztra.mockpg.saga.SagaTokenFilter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.assertj.core.api.Assertions.assertThat;

class SagaTokenFilterTest {
    @Test void blankTokenRefusesEvenAnEmptyBearerCredential() throws Exception {
        var request = new MockHttpServletRequest("POST", "/mock-pg/saga/operations/test");
        request.addHeader("Authorization", "Bearer ");
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        new SagaTokenFilter("").doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(chain.getRequest()).isNull();
    }
    @Test void healthDoesNotRequireASagaCredential() throws Exception {
        var request = new MockHttpServletRequest("GET", "/mock-pg/health");
        var chain = new MockFilterChain();
        new SagaTokenFilter("").doFilter(request, new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest()).isSameAs(request);
    }
}
