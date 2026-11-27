package dev.sylvain.planning.service.demo;

import dev.sylvain.planning.config.DemoInstance;
import dev.sylvain.planning.service.BusinessError;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Refuses the call on a demo instance — see {@link RefusedOnDemoInstance}. In memory, before any transaction. */
@RefusedOnDemoInstance
@Interceptor
@Priority(Interceptor.Priority.PLATFORM_BEFORE + 150)
public class RefusedOnDemoInstanceInterceptor {

    private final boolean demoInstance;

    @Inject
    RefusedOnDemoInstanceInterceptor(
            @ConfigProperty(name = "planning.demo-instance", defaultValue = "false") boolean demoInstance) {
        this.demoInstance = demoInstance;
    }

    @AroundInvoke
    Object refuseOnDemo(InvocationContext context) throws Exception {
        if (demoInstance) {
            RefusedOnDemoInstance binding = context.getInterceptorBinding(RefusedOnDemoInstance.class);
            throw new BusinessError.Conflict(DemoInstance.REFUSAL + (binding == null ? "" : binding.value()));
        }
        return context.proceed();
    }
}
