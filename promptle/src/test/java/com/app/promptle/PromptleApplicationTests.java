package com.app.promptle;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class PromptleApplicationTests {

	@Test
	@Disabled("requires running PostgreSQL — integration only")
	void contextLoads() {
	}

}
