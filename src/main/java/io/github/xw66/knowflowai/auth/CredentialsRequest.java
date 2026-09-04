package io.github.xw66.knowflowai.auth;

import java.nio.charset.StandardCharsets;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CredentialsRequest(
        @NotBlank(message = "用户名不能为空")
        @Pattern(regexp = "[A-Za-z0-9_]{3,64}", message = "用户名须为 3–64 位英文字母、数字或下划线")
        String username,
        @NotBlank(message = "密码不能为空")
        @Size(min = 12, max = 72, message = "密码须为 12–72 个字符，且 UTF-8 编码不超过 72 字节")
        String password) {

    // 注册和登录共用字节上限，避免 BCrypt 对多字节密码抛出内部异常。
    @AssertTrue(message = "密码的 UTF-8 编码不能超过 72 字节")
    public boolean isPasswordWithinByteLimit() {
        return password == null || password.getBytes(StandardCharsets.UTF_8).length <= 72;
    }

    @Override
    public String toString() {
        return "CredentialsRequest[password=已隐藏]";
    }
}
