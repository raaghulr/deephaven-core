//
// Copyright (c) 2016-2025 Deephaven Data Labs and Patent Pending
//
package io.deephaven.extensions.s3;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CredentialsTest {

    @Test
    void defaultCredentials() {
        isCredentials(Credentials.defaultCredentials());
    }

    @Test
    void basic() {
        isCredentials(Credentials.basic("accessKeyId", "secretAccessKey"));
    }

    @Test
    void session() {
        isCredentials(Credentials.session("accessKeyId", "secretAccessKey", "sessionToken"));
    }

    @Test
    void anonymous() {
        isCredentials(Credentials.anonymous());
    }

    private void isCredentials(Credentials c) {
        assertThat(c).isInstanceOf(AwsSdkV2Credentials.class);
    }
}
