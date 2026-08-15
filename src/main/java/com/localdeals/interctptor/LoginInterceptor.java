package com.localdeals.interctptor;

import com.localdeals.utils.UserHolder;
import org.springframework.http.HttpMethod;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;


public class LoginInterceptor implements HandlerInterceptor {
    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    private final List<String> publicGetPaths;
    private final List<String> publicPostPaths;

    public LoginInterceptor() {
        this(Collections.emptyList(), Collections.emptyList());
    }

    public LoginInterceptor(List<String> publicGetPaths) {
        this(publicGetPaths, Collections.emptyList());
    }

    public LoginInterceptor(List<String> publicGetPaths, List<String> publicPostPaths) {
        this.publicGetPaths = Collections.unmodifiableList(new ArrayList<>(publicGetPaths));
        this.publicPostPaths = Collections.unmodifiableList(new ArrayList<>(publicPostPaths));
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (isPublicRequest(request)) {
            return true;
        }
        //前一个拦截器大部分逻辑已经实现了，那么现在就是只需要判断是否需要拦截，
        // thredlocal 中是否有用户
        if(UserHolder.getUser() == null){
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }
        // 放行
        return true;
    }

    private boolean isPublicRequest(HttpServletRequest request) {
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (HttpMethod.GET.matches(request.getMethod()) || HttpMethod.HEAD.matches(request.getMethod())) {
            return matchesAny(publicGetPaths, path);
        }
        return HttpMethod.POST.matches(request.getMethod()) && matchesAny(publicPostPaths, path);
    }

    private boolean matchesAny(List<String> patterns, String path) {
        return patterns.stream().anyMatch(pattern -> PATH_MATCHER.match(pattern, path));
    }
}
