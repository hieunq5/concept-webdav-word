package com.mgm.attachmenteditor.config;

import com.mgm.attachmenteditor.service.DocumentStorageService;
import com.mgm.attachmenteditor.webdav.WebDavServlet;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class WebDavConfig {

    /**
     * Registered directly as a servlet (not a Spring MVC controller) at
     * /webdav/*, a more specific mapping than DispatcherServlet's default "/"
     * mapping, so the container routes WebDAV verbs here without any change
     * to Spring MVC's own routing.
     */
    @Bean
    public ServletRegistrationBean<WebDavServlet> webDavServletRegistrationBean(DocumentStorageService storage) {
        ServletRegistrationBean<WebDavServlet> registration =
                new ServletRegistrationBean<>(new WebDavServlet(storage), "/webdav/*");
        registration.setName("webdav");
        return registration;
    }
}
