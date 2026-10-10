package com.staypoint.mockpg;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.staypoint.common.error.BusinessException;
import com.staypoint.common.error.ErrorCode;
import com.staypoint.mockpg.MockPgRepository.MockPgCancel;

/** 모의 PG 의 승인·취소 처리. 실패율 판단은 호출한 쪽(컨트롤러)이 넘겨준다. */
@Service
class MockPgService {

	private final MockPgRepository repository;

	MockPgService(MockPgRepository repository) {
		this.repository = repository;
	}

	/** created=false 면 같은 orderId 의 이전 결과를 그대로 돌려준 것 (이중 승인 없음). */
	record ApproveOutcome(MockPgPayment payment, boolean created) {
	}

	record CancelOutcome(MockPgPayment payment, BigDecimal cancelAmount) {
	}

	@Transactional
	public ApproveOutcome approve(String orderId, BigDecimal amount, double failRate) {
		// nextDouble() 은 [0, 1) → failRate 0 이면 항상 승인, 1 이면 항상 거절
		boolean approved = ThreadLocalRandom.current().nextDouble() >= failRate;
		String tid = approved ? "T" + UUID.randomUUID().toString().replace("-", "") : null;
		boolean created = repository.insertPaymentIfAbsent(orderId, tid, amount,
				approved ? MockPgPayment.APPROVED : MockPgPayment.FAILED);
		return new ApproveOutcome(repository.findPayment(orderId).orElseThrow(), created);
	}

	@Transactional(readOnly = true)
	public MockPgPayment find(String orderId) {
		return repository.findPayment(orderId)
				.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "결제 기록이 없습니다.", Map.of("orderId", orderId)));
	}

	/**
	 * 데모 장애 주입 (설계 5장 T20). failTimes 가 있으면 이 결제의 남은 실패 횟수를 새로 설정하고,
	 * 남은 횟수가 있으면 1 줄이고 이번 호출이 실패해야 할 장애 종류를 돌려준다.
	 * 실패 응답은 이 트랜잭션이 커밋된 뒤 컨트롤러가 던진다 — 여기서 던지면 차감까지 롤백된다.
	 */
	@Transactional
	public Optional<MockPgCancelFault> takeCancelFault(String tid, Integer failTimes, MockPgCancelFault failType) {
		if (failTimes != null) {
			repository.setCancelFault(tid, failTimes, failType);
		}
		return repository.consumeCancelFault(tid);
	}

	/**
	 * 전액·부분 취소. 같은 cancelKey 가 다시 오면 새로 취소하지 않고 처음 결과를 돌려준다
	 * (우리 서비스가 타임아웃 후 재시도해도 두 번 환불되지 않음).
	 */
	@Transactional
	public CancelOutcome cancel(String tid, String cancelKey, BigDecimal amount) {
		MockPgPayment payment = repository.lockPaymentByTid(tid)
				.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "승인 기록이 없습니다.", Map.of("tid", tid)));

		Optional<MockPgCancel> previous = repository.findCancel(cancelKey);
		if (previous.isPresent()) {
			if (!previous.get().tid().equals(tid)) {
				throw new BusinessException(ErrorCode.VALIDATION_FAILED, "다른 결제에 사용된 cancelKey 입니다.",
						Map.of("cancelKey", cancelKey));
			}
			return new CancelOutcome(payment, previous.get().amount());
		}

		if (!MockPgPayment.APPROVED.equals(payment.status())) {
			throw new BusinessException(ErrorCode.INVALID_STATE, "취소할 수 없는 결제 상태입니다: " + payment.status(),
					Map.of("status", payment.status()));
		}
		if (amount.compareTo(payment.remainingAmount()) > 0) {
			throw new BusinessException(ErrorCode.CANCEL_AMOUNT_EXCEEDED, ErrorCode.CANCEL_AMOUNT_EXCEEDED.defaultMessage(),
					Map.of("requested", amount, "remaining", payment.remainingAmount()));
		}

		repository.insertCancel(cancelKey, tid, amount);
		BigDecimal canceled = payment.canceledAmount().add(amount);
		String status = canceled.compareTo(payment.amount()) == 0 ? MockPgPayment.CANCELED : MockPgPayment.APPROVED;
		repository.updateCanceled(tid, canceled, status);
		return new CancelOutcome(repository.findPayment(payment.orderId()).orElseThrow(), amount);
	}
}
