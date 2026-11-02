package com.e2eq.framework.service.access;

import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
@DefaultBean
public class NoOpAccessInviteProvisioner implements AccessInviteProvisioner {
}
