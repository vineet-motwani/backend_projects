package com.resume.gateflux;

import com.aerospike.client.IAerospikeClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
class GatefluxApplicationTests {

    @MockitoBean
    private IAerospikeClient aerospikeClient;

    @Test
    void contextLoads() {
    }

}
