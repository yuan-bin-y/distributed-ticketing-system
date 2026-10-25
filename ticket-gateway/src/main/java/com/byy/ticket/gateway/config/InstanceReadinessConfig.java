package com.byy.ticket.gateway.config;

import com.byy.ticket.routing.ReadinessLoadBalancerConfig;

import org.springframework.cloud.loadbalancer.annotation.LoadBalancerClients;
import org.springframework.context.annotation.Configuration;

@Configuration
@LoadBalancerClients(defaultConfiguration = ReadinessLoadBalancerConfig.class)
public class InstanceReadinessConfig {}
