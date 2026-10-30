package com.latestay.owner;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Fills an {@link OwnerHotel} parameter from the ls_owner cookie, or answers 401. Registered by WebConfig (not a
 * component, so web-slice tests without the owner beans still start).
 */
public class OwnerHotelResolver implements HandlerMethodArgumentResolver {

    private final OwnerSessions sessions;

    public OwnerHotelResolver(OwnerSessions sessions) {
        this.sessions = sessions;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType() == OwnerHotel.class;
    }

    @Override
    public OwnerHotel resolveArgument(MethodParameter parameter, ModelAndViewContainer mav, NativeWebRequest request,
            WebDataBinderFactory binderFactory) {
        return sessions.current(request.getNativeRequest(HttpServletRequest.class))
                .orElseThrow(OwnerNotSignedInException::new);
    }
}
