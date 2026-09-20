package com.localdeals.trade.sharding;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcUrlsTest {

    private static final String URL =
            "jdbc:mysql://localhost:3306/local_deals?useSSL=false&serverTimezone=UTC";

    @Test
    void theSchemaIsTheNameBetweenTheHostAndTheParameters() {
        assertThat(JdbcUrls.schemaOf(URL)).isEqualTo("local_deals");
        assertThat(JdbcUrls.schemaOf("jdbc:mysql://db:3306/local_deals")).isEqualTo("local_deals");
    }

    @Test
    void aSecondDatabaseIsTheSameServerAndTheSameParameters() {
        assertThat(JdbcUrls.withSchema(URL, "local_deals_1"))
                .isEqualTo("jdbc:mysql://localhost:3306/local_deals_1?useSSL=false&serverTimezone=UTC");
        assertThat(JdbcUrls.withSchema("jdbc:mysql://db:3306/local_deals", "local_deals_1"))
                .isEqualTo("jdbc:mysql://db:3306/local_deals_1");
    }

    @Test
    void aUrlNamingNoSchemaIsRefusedInsteadOfGuessed() {
        assertThatThrownBy(() -> JdbcUrls.schemaOf("jdbc:mysql://localhost:3306"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("schema");
        assertThatThrownBy(() -> JdbcUrls.schemaOf("jdbc:mysql://localhost:3306/?useSSL=false"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
