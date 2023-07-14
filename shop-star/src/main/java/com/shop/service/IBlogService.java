package com.shop.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.shop.dto.Result;
import com.shop.entity.Blog;

/**
 * <p>
 * 服务类
 * </p>
 *
 * @since 2021-12-22
 */
public interface IBlogService extends IService<Blog> {

    Result queryBlogById(Long id);

    Result queryHotBlog(Integer current);

    Result likeBlog(Long id);

    Result queryBlogLikes(Long id);

    Result saveBlog(Blog blog);

    Result queryBlogFollow(Long max, Integer offset);
}
