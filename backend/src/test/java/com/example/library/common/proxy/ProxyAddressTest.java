package com.example.library.common.proxy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class ProxyAddressTest {
    @ParameterizedTest
    @ValueSource(strings = {"localhost", "192.168.1.1", "127.0.0.1", "::1", "2001:DB8::1", "::ffff:192.0.2.1", "proxy.example", "bücher.example"})
    void acProxy01TrustedLocalPrivateAndIdnHostsDoNotNeedDnsLookup(String host) {
        assertThat(ProxyAddress.normalize(host)).isNotBlank();
    }
    @ParameterizedTest
    @ValueSource(strings = {"localhost:9999", "http://proxy", "u@proxy", "proxy/path", "proxy?x", "*.example", "[::1]", "fe80::1%lo0", "proxy\u0000", "proxy\n", "bad host", "-bad.example", "bad-.example", "bad..example", "256.1.1.1", "1.2.3", "01.2.3.4", "2001::xyz", "2001:::1", "a:b"})
    void acProxy01RejectsUrlControlWildcardEmbeddedPortAndMalformedAddresses(String host) {
        assertThatThrownBy(() -> ProxyAddress.normalize(host)).isInstanceOf(IllegalArgumentException.class);
    }
}
