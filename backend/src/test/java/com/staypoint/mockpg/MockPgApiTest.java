package com.staypoint.mockpg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;
import com.staypoint.TestcontainersConfiguration;
import com.staypoint.support.TestFixtures;

/**
 * 모의 PG API (설계 5장, FR-PAY-1~3): 승인 · 조회 · 취소, 장애 주입(실패율·지연), orderId · cancelKey 멱등.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class MockPgApiTest {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbc;

	@BeforeEach
	void setUp() {
		new TestFixtures(jdbc).truncateAll();
	}

	@Test
	void 실패율_0이면_승인되고_tid_가_발급된다() throws Exception {
		approve("O-1", 100_000, "?failRate=0")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("APPROVED"))
				.andExpect(jsonPath("$.tid").isNotEmpty())
				.andExpect(jsonPath("$.amount").value(100_000));
	}

	@Test
	void 실패율_1이면_거절되고_tid_가_없다() throws Exception {
		approve("O-1", 100_000, "?failRate=1")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("FAILED"))
				.andExpect(jsonPath("$.tid").doesNotExist());
	}

	@Test
	void 같은_orderId_로_다시_승인을_요청하면_처음_결과를_그대로_돌려준다() throws Exception {
		String first = approve("O-1", 100_000, "?failRate=0").andReturn().getResponse().getContentAsString();
		// 두 번째는 실패율 100% 로 보내도 처음 결과(승인)가 유지된다 → 이중 승인도, 결과 뒤집힘도 없다
		String second = approve("O-1", 100_000, "?failRate=1").andReturn().getResponse().getContentAsString();

		assertThat(second).isEqualTo(first);
		assertThat(count("mockpg_payment")).isEqualTo(1);
	}

	@Test
	void 거절된_orderId_는_재요청해도_거절_결과를_돌려준다() throws Exception {
		approve("O-1", 100_000, "?failRate=1").andExpect(jsonPath("$.status").value("FAILED"));

		approve("O-1", 100_000, "?failRate=0").andExpect(jsonPath("$.status").value("FAILED"));
	}

	@Test
	void delayMs_만큼_늦게_응답하지만_승인은_응답_전에_이미_기록되어_있다() throws Exception {
		long started = System.nanoTime();
		approve("O-1", 100_000, "?failRate=0&delayMs=700").andExpect(status().isOk());
		long elapsedMs = (System.nanoTime() - started) / 1_000_000;

		assertThat(elapsedMs).isGreaterThanOrEqualTo(700);
	}

	@Test
	void 지연_중에도_조회_API_로는_이미_승인된_것이_보인다() throws Exception {
		// 호출한 쪽이 타임아웃으로 응답을 못 받아도 PG 에는 승인이 남는 상황 (설계 4.4)
		ExecutorService executor = Executors.newSingleThreadExecutor();
		Future<?> slow = executor.submit(() -> approve("O-1", 100_000, "?failRate=0&delayMs=2000"));
		awaitRow("O-1");

		mockMvc.perform(get("/mock-pg/payments/O-1"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("APPROVED"));
		slow.get(10, TimeUnit.SECONDS);
		executor.shutdown();
	}

	@Test
	void 기록이_없는_orderId_조회는_404() throws Exception {
		mockMvc.perform(get("/mock-pg/payments/NOPE"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOT_FOUND"));
	}

	@Test
	void 실패율이_0에서_1_범위를_벗어나면_400() throws Exception {
		approve("O-1", 100_000, "?failRate=1.5")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		assertThat(count("mockpg_payment")).isZero();
	}

	@Test
	void 부분_취소를_나눠_하면_잔액이_줄고_전액이_취소되면_CANCELED_가_된다() throws Exception {
		String tid = approvedTid("O-1", 100_000);

		cancel(tid, "C-1", 30_000, "")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.cancelAmount").value(30_000))
				.andExpect(jsonPath("$.remainingAmount").value(70_000))
				.andExpect(jsonPath("$.status").value("APPROVED"));
		cancel(tid, "C-2", 70_000, "")
				.andExpect(jsonPath("$.remainingAmount").value(0))
				.andExpect(jsonPath("$.status").value("CANCELED"));
	}

	@Test
	void 남은_승인_금액보다_많이_취소하면_거부한다() throws Exception {
		String tid = approvedTid("O-1", 100_000);
		cancel(tid, "C-1", 30_000, "");

		cancel(tid, "C-2", 70_001, "")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("CANCEL_AMOUNT_EXCEEDED"));
		assertThat(canceledAmount("O-1")).isEqualByComparingTo("30000");
	}

	@Test
	void 같은_cancelKey_로_다시_취소하면_이전_결과를_돌려주고_두_번_취소되지_않는다() throws Exception {
		String tid = approvedTid("O-1", 100_000);

		cancel(tid, "C-1", 30_000, "").andExpect(jsonPath("$.remainingAmount").value(70_000));
		cancel(tid, "C-1", 30_000, "")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.cancelAmount").value(30_000))
				.andExpect(jsonPath("$.remainingAmount").value(70_000));

		assertThat(canceledAmount("O-1")).isEqualByComparingTo("30000");
		assertThat(count("mockpg_cancel")).isEqualTo(1);
	}

	@Test
	void 취소_실패율_1이면_503_이고_아무것도_기록되지_않아_같은_키로_재시도할_수_있다() throws Exception {
		String tid = approvedTid("O-1", 100_000);

		cancel(tid, "C-1", 100_000, "?failRate=1")
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.code").value("PG_UNAVAILABLE"));
		assertThat(count("mockpg_cancel")).isZero();

		cancel(tid, "C-1", 100_000, "?failRate=0")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("CANCELED"));
	}

	@Test
	void failTimes_를_주면_그_결제의_취소가_파라미터_없는_이후_요청까지_정확히_N번_실패한다() throws Exception {
		String tid = approvedTid("O-1", 100_000);

		cancel(tid, "C-1", 100_000, "?failTimes=2").andExpect(status().isServiceUnavailable());
		cancel(tid, "C-1", 100_000, "").andExpect(status().isServiceUnavailable()); // 재시도에도 장애가 이어짐
		assertThat(count("mockpg_cancel")).isZero();

		cancel(tid, "C-1", 100_000, "")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("CANCELED"));
	}

	@Test
	void failType_REJECTED_는_422_로_거절하고_횟수를_다_쓰면_취소된다() throws Exception {
		String tid = approvedTid("O-1", 100_000);

		cancel(tid, "C-1", 100_000, "?failTimes=1&failType=REJECTED")
				.andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.code").value("PG_REJECTED"));

		cancel(tid, "C-1", 100_000, "").andExpect(status().isOk());
		assertThat(jdbc.queryForObject("SELECT cancel_fail_remaining FROM mockpg_payment", Integer.class)).isZero();
	}

	@Test
	void 없는_tid_취소는_404_거절된_결제는_취소할_수_없다() throws Exception {
		cancel("T-NOPE", "C-1", 1_000, "").andExpect(status().isNotFound());

		approve("O-2", 100_000, "?failRate=1");
		jdbc.update("UPDATE mockpg_payment SET tid = 'T-FAILED' WHERE order_id = 'O-2'");
		cancel("T-FAILED", "C-2", 1_000, "")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("INVALID_STATE"));
	}

	@Test
	void 같은_orderId_로_동시에_5번_승인을_요청해도_기록은_하나이고_모두_같은_결과를_받는다() throws Exception {
		List<String> responses = runConcurrently(5, () ->
				approve("O-1", 100_000, "?failRate=0.5").andReturn().getResponse().getContentAsString());

		assertThat(new HashSet<>(responses)).hasSize(1);
		assertThat(count("mockpg_payment")).isEqualTo(1);
	}

	@Test
	void 같은_cancelKey_로_동시에_5번_취소해도_한_번만_취소된다() throws Exception {
		String tid = approvedTid("O-1", 100_000);

		List<String> responses = runConcurrently(5, () ->
				cancel(tid, "C-1", 40_000, "").andReturn().getResponse().getContentAsString());

		Set<Integer> remaining = new HashSet<>();
		responses.forEach(body -> remaining.add(JsonPath.read(body, "$.remainingAmount")));
		assertThat(remaining).containsExactly(60_000);
		assertThat(canceledAmount("O-1")).isEqualByComparingTo("40000");
		assertThat(count("mockpg_cancel")).isEqualTo(1);
	}

	private ResultActions approve(String orderId, int amount, String query) throws Exception {
		return mockMvc.perform(post("/mock-pg/payments/approve" + query)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"orderId\": \"%s\", \"amount\": %d}".formatted(orderId, amount)));
	}

	private ResultActions cancel(String tid, String cancelKey, int amount, String query) throws Exception {
		return mockMvc.perform(post("/mock-pg/payments/" + tid + "/cancel" + query)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"cancelKey\": \"%s\", \"amount\": %d}".formatted(cancelKey, amount)));
	}

	private String approvedTid(String orderId, int amount) throws Exception {
		String body = approve(orderId, amount, "?failRate=0").andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.tid");
	}

	private BigDecimal canceledAmount(String orderId) {
		return jdbc.queryForObject("SELECT canceled_amount FROM mockpg_payment WHERE order_id = ?", BigDecimal.class, orderId);
	}

	private int count(String table) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
	}

	private void awaitRow(String orderId) throws InterruptedException {
		for (int i = 0; i < 100; i++) {
			if (jdbc.queryForObject("SELECT COUNT(*) FROM mockpg_payment WHERE order_id = ?", Integer.class, orderId) > 0) {
				return;
			}
			Thread.sleep(20);
		}
		throw new AssertionError("승인 기록이 생기지 않았습니다: " + orderId);
	}

	interface Call {
		String run() throws Exception;
	}

	private static List<String> runConcurrently(int threads, Call call) throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(threads);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<String>> futures = new ArrayList<>();
		for (int i = 0; i < threads; i++) {
			futures.add(executor.submit(() -> {
				start.await();
				return call.run();
			}));
		}
		start.countDown();
		List<String> results = new ArrayList<>();
		for (Future<String> f : futures) {
			results.add(f.get(30, TimeUnit.SECONDS));
		}
		executor.shutdown();
		return results;
	}
}
