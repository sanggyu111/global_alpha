# CLAUDE.md

이 프로젝트의 공통 규칙은 `AGENT.md` 에 있다. 아래 import 로 전체 내용을 불러온다.
규칙 변경은 `AGENT.md` 에서만 하고, 이 파일에는 Claude Code 전용 사항만 둔다.

@AGENT.md

---

## Claude Code 전용

- 커밋/PR 생성 시 **`Co-Authored-By: Claude` 줄과 "Generated with Claude Code" 문구를 넣지 않는다.** (AGENT.md 10장)
- 동시성·결제·환불 관련 코드를 변경하면 해당 테스트를 실행해 결과를 확인한 뒤 보고한다.
- 설계 결정을 내리면 README 의 해당 질문 섹션에 반영할 내용을 함께 제시한다.
