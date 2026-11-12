package com.surf.ledger.auth;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final AuthInterceptor auth;
    private final AdminInterceptor admin;

    public WebConfig(AuthInterceptor auth, AdminInterceptor admin) {
        this.auth = auth;
        this.admin = admin;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(auth)
                .addPathPatterns("/api/**", "/auth/me", "/auth/logout", "/auth/wx/unbind");
        registry.addInterceptor(admin)
                .addPathPatterns("/admin/**");
    }
}
