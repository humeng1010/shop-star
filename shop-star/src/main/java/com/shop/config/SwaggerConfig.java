package com.shop.config;

import com.github.xiaoymin.knife4j.spring.annotations.EnableKnife4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import springfox.documentation.builders.ApiInfoBuilder;
import springfox.documentation.builders.PathSelectors;
import springfox.documentation.builders.RequestHandlerSelectors;
import springfox.documentation.service.ApiInfo;
import springfox.documentation.service.Contact;
import springfox.documentation.spi.DocumentationType;
import springfox.documentation.spring.web.plugins.Docket;
import springfox.documentation.swagger2.annotations.EnableSwagger2WebMvc;

/**
 * 自定义Swagger接口文档的配置
 *
 * @author humeng
 */
@Configuration
@EnableSwagger2WebMvc
@EnableKnife4j
// 用于指定在开发环境加载这个Bean,或者这个配置使用yml配置文件的方式
@Profile({"dev"})
// https://doc.xiaominfo.com/docs/features/accessControl#351-%E7%94%9F%E4%BA%A7%E7%8E%AF%E5%A2%83%E5%B1%8F%E8%94%BD%E8%B5%84%E6%BA%90
public class SwaggerConfig {

    @Bean(value = "defaultApi2")
    public Docket createRestApi() {
        return new Docket(DocumentationType.SWAGGER_2)
                .apiInfo(apiInfo())
                .select()
                .apis(RequestHandlerSelectors.basePackage("com.shop.controller"))
                .paths(PathSelectors.any())
                .build();
    }

    /**
     * api信息
     *
     * @return api info
     */
    private ApiInfo apiInfo() {
        return new ApiInfoBuilder()
                .title("店铺点评API")
                .description("接口文档")
                .termsOfServiceUrl("https://github.com/humeng1010")
                .contact(new Contact("humeng", "https://github.com/humeng1010", "humeng_mail@163.com"))
                .version("1.0")
                .build();
    }
}
