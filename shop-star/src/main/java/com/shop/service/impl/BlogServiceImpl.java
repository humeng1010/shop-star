package com.shop.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.shop.dto.Result;
import com.shop.dto.UserDTO;
import com.shop.entity.Blog;
import com.shop.entity.User;
import com.shop.mapper.BlogMapper;
import com.shop.service.IBlogService;
import com.shop.service.IUserService;
import com.shop.utils.RedisConstants;
import com.shop.utils.SystemConstants;
import com.shop.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletResponse;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private HttpServletResponse response;

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(record -> {
            this.queryBlogUser(record);
            this.isBlogLiked(record);
        });
        return Result.ok(records);
    }

    @Override
    public Result likeBlog(Long id) {
        if (id == null || id <= 0) {
            return Result.fail("笔记不存在");
        }
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            response.setStatus(401);
            return Result.fail("用户未登录");
        }
        Long userId = user.getId();

        // 判断当前登录用户是否已经点赞
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if (score == null) {
            // 如果没有点赞,则点赞
            boolean isSuccess = update()
                    .setSql("liked = liked + 1")
                    .eq("id", id)
                    .update();
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
                return Result.ok("点赞成功");
            }
            return Result.fail("点赞失败");
        }

        // 如果已经点赞,则取消
        boolean isSuccess = update()
                .setSql("liked = liked - 1")
                .eq("id", id)
                .update();
        if (isSuccess) {
            stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            return Result.ok("取消点赞");
        }

        return Result.fail("取消失败");
    }

    /**
     * 查询该笔记点赞用户TOP5
     *
     * @param id blog id
     * @return list
     */
    @Override
    public Result queryBlogLikes(Long id) {
        if (id == null || id <= 0) {
            return Result.fail("笔记不存在");
        }

        String key = RedisConstants.BLOG_LIKED_KEY + id;
        Set<String> userIds = stringRedisTemplate.opsForZSet().range(key, 0, 4);

        if (userIds == null || userIds.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }

        List<Long> ids = userIds.stream().map(Long::valueOf).collect(Collectors.toList());
        String idStr = StrUtil.join(",", ids);
        List<UserDTO> userDTOS = userService
                .query().in("id", ids)
                .last("order by field(id," + idStr + ")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class)).collect(Collectors.toList());

        return Result.ok(userDTOS);
    }

    @Override
    public Result queryBlogById(Long id) {
        Blog blog = this.getById(id);
        if (blog == null) {
            return Result.fail("笔记不存在");
        }
        this.queryBlogUser(blog);
        this.isBlogLiked(blog);

        return Result.ok(blog);
    }

    private void isBlogLiked(Blog blog) {
        // 从redis中查询是否有数据,有则代表已经点赞 true 否则false
        String key = RedisConstants.BLOG_LIKED_KEY + blog.getId();
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return;
        }
        Long userId = user.getId();
        Double score = stringRedisTemplate.opsForZSet()
                .score(key, userId.toString());
        blog.setIsLike(score != null);
    }

    /**
     * 查询该blog用户的信息
     *
     * @param blog
     */
    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
}
