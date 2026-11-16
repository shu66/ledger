package com.surf.ledger.auth;

import com.surf.ledger.common.ApiException;
import com.surf.ledger.common.ClientIp;
import com.surf.ledger.common.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/auth")
public class AuthController {
    private final AuthService service;
    private final ClientIp clientIp;
    private final RateLimiter loginLimiter = new RateLimiter(20, 60_000);
    private final RateLimiter registerLimiter = new RateLimiter(5, 600_000);

    public AuthController(AuthService service, ClientIp clientIp) {
        this.service = service;
        this.clientIp = clientIp;
    }

    public record RegisterReq(String email, String password, String nickname) {}
    public record LoginReq(String email, String password) {}
    public record WxLoginReq(String code) {}
    public record BindReq(String bindToken, String email, String password) {}
    public record WxRegisterReq(String bindToken, String email, String password, String nickname) {}
    public record ForgotReq(String email) {}
    public record ResetReq(String token, String password) {}

    @PostMapping("/register")
    public Map<String, Object> register(@RequestBody RegisterReq r, HttpServletRequest http) {
        guard(registerLimiter, http);
        return service.register(r.email(), r.password(), r.nickname());
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody LoginReq r, HttpServletRequest http) {
        guard(loginLimiter, http);
        return service.login(r.email(), r.password());
    }

    @PostMapping("/wx/login")
    public Map<String, Object> wxLogin(@RequestBody WxLoginReq r, HttpServletRequest http) {
        guard(loginLimiter, http);
        if (r.code() == null || r.code().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "NO_CODE");
        }
        return service.wxLogin(r.code());
    }

    @PostMapping("/wx/bind")
    public Map<String, Object> bind(@RequestBody BindReq r, HttpServletRequest http) {
        guard(loginLimiter, http);
        return service.bindWx(r.bindToken(), r.email(), r.password());
    }

    /** 微信首次登录：创建账号并绑定，一次成功 */
    @PostMapping("/wx/register")
    public Map<String, Object> wxRegister(@RequestBody WxRegisterReq r, HttpServletRequest http) {
        guard(registerLimiter, http);
        return service.wxRegister(r.bindToken(), r.email(), r.password(), r.nickname());
    }

    @PostMapping("/wx/unbind")
    public Map<String, Object> unbind(@RequestAttribute(AuthInterceptor.UID) long uid) {
        service.unbindWx(uid);
        return Map.of("ok", true);
    }

    /** 登出：吊销当前账号的所有访问令牌 */
    @PostMapping("/logout")
    public Map<String, Object> logout(@RequestAttribute(AuthInterceptor.UID) long uid) {
        service.logout(uid);
        return Map.of("ok", true);
    }

    @GetMapping("/me")
    public Map<String, Object> me(@RequestAttribute(AuthInterceptor.UID) long uid) {
        return service.me(uid);
    }

    @PostMapping("/password/forgot")
    public Map<String, Object> forgot(@RequestBody ForgotReq r, HttpServletRequest http) {
        guard(registerLimiter, http);
        service.requestReset(r.email());
        return Map.of("ok", true);
    }

    @PostMapping("/password/reset")
    public Map<String, Object> reset(@RequestBody ResetReq r, HttpServletRequest http) {
        guard(registerLimiter, http);
        service.resetPassword(r.token(), r.password());
        return Map.of("ok", true);
    }

    private void guard(RateLimiter limiter, HttpServletRequest http) {
        if (!limiter.allow(clientIp.of(http))) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY");
        }
    }
}
