package com.settleup.backend;

import com.settleup.backend.dev.DevSeed;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;

import static org.junit.jupiter.api.Assertions.assertTrue;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class BackendApplicationTests {

	@Autowired ApplicationContext context;

	@Test
	void contextLoads() {
	}

	@Test
	void devSeedIsAbsentWithoutDevProfile() {
		assertTrue(context.getBeansOfType(DevSeed.class).isEmpty());
	}

}
