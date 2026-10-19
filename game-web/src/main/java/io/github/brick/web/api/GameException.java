package io.github.brick.web.api;

public class GameException extends RuntimeException {
    private final int status;
    private final String code;

    public GameException(int status, String code, String publicMessage) {
        super(publicMessage);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public static GameException invalid() {
        return new GameException(400, "INVALID_ARGUMENT", "参数不合法");
    }

    public static GameException unauthorized() {
        return new GameException(401, "UNAUTHORIZED", "账号或凭证无效");
    }

    public static GameException integrity() {
        return new GameException(500, "DATA_INTEGRITY", "数据状态异常，请联系管理员");
    }
}
