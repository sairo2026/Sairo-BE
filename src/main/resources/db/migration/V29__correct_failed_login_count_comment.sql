-- Corrects the description of app_user.failed_login_count so it states that login locking is counted per account email.
-- Only a column COMMENT changes. V28 and earlier are not modified. No schema object is changed and no rows are inserted, updated or deleted.

COMMENT ON COLUMN app_user.failed_login_count IS '이메일 로그인에 실패할 때마다 늘어나는 해당 계정의 실패 횟수다. 로그인에 성공하거나 비밀번호를 바꾸면 0이 된다. 로그인 잠금은 이 값이 아니라 서비스 계층이 정규화한 이메일 단위로 세는 카운터가 판단하며 접속 IP는 기준에 들어가지 않는다';
