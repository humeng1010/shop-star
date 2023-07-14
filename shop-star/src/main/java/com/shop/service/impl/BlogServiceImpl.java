package com.shop.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.shop.dto.Result;
import com.shop.dto.ScrollResult;
import com.shop.dto.UserDTO;
import com.shop.entity.Blog;
import com.shop.entity.Follow;
import com.shop.entity.User;
import com.shop.mapper.BlogMapper;
import com.shop.service.IBlogService;
import com.shop.service.IFollowService;
import com.shop.service.IUserService;
import com.shop.utils.RedisConstants;
import com.shop.utils.SystemConstants;
import com.shop.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import static com.shop.utils.RedisConstants.BLOG_LIKED_KEY;

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

    @Resource
    private BlogMapper blogMapper;

    @Resource
    private IFollowService followService;

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
            // 从缓存中获取最新的点赞数
            Long id = record.getId();
            Long likeCount = getLikeCountFromRedis(id);

            record.setLiked(Math.toIntExact(likeCount));

            this.queryBlogUser(record);
            this.isBlogLiked(record);
        });
        records.sort((o1, o2) -> o2.getLiked() - o1.getLiked());
        return Result.ok(records);
    }

    private Long getLikeCountFromRedis(Long id) {
        Long likeCount = stringRedisTemplate.opsForZSet().size(BLOG_LIKED_KEY + id);
        if (likeCount == null) {
            //    没有人点赞
            likeCount = 0L;
        }
        return likeCount;
    }


    /**
     * 定时任务，把点赞数统计到数据库。 每⼀分钟执行一次
     */
    @Scheduled(cron = "0 0/1 * * * ?")
    public void init() {
        // 获取数据库中所有的订单id
        QueryWrapper<Blog> queryWrapper = new QueryWrapper<>();
        queryWrapper.select("id", "liked");
        List<Blog> blogs = blogMapper.selectList(queryWrapper);
        for (Blog blog : blogs) {
            Long id = blog.getId();
            // 获取当前id在set集合中的点赞数
            Long likeCount = getLikeCountFromRedis(id);
            long liked = blog.getLiked();
            // 缓存的like总数大于等于0并且缓存中的点赞数不等于数据库中的点赞数,才做更新
            if (likeCount >= 0 && likeCount != liked) {
                // 更新数据库中的点赞数
                this.lambdaUpdate()
                        .set(Blog::getLiked, likeCount)
                        .eq(Blog::getId, id)
                        .update();
            }
        }
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
        String key = BLOG_LIKED_KEY + id;
        // 如果zset中没有该用户的时间戳,说明没有点赞(没有原子性,存在并发问题,可以使用Lua脚本保证原子性)
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if (score == null) {
            // 可以点赞,保存当前时间戳作为分数
            stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            return Result.ok("点赞成功");
        }

        // 已经点赞过了,就取消点赞
        stringRedisTemplate.opsForZSet().remove(key, userId.toString());
        return Result.ok("取消点赞");
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

        String key = BLOG_LIKED_KEY + id;
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
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());

        return Result.ok(userDTOS);
    }

    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("请先登录");
        }
        Long userId = user.getId();
        blog.setUserId(userId);
        // 保存探店博文
        boolean isSuccess = save(blog);
        if (!isSuccess) {
            return Result.fail("新增笔记失败");
        }
        // 博客id
        Long blogId = blog.getId();
        // 查询发布笔记用户的粉丝
        List<Follow> fans = followService.query().eq("follow_user_id", userId).list();
        // 开启异步任务
        CompletableFuture.runAsync(() -> {
            // 推送笔记id给所有粉丝的收信箱-收信箱的内容为 笔记id-时间戳
            for (Follow fan : fans) {
                // 粉丝id
                Long fansId = fan.getUserId();
                //    推送
                String key = RedisConstants.FEED_KEY + fansId;
                stringRedisTemplate.opsForZSet().add(key, blogId.toString(), System.currentTimeMillis());
            }
        });

        // 返回id
        return Result.ok(blogId);
    }

    @Override
    public Result queryBlogFollow(Long max, Integer offset) {
        // 获取当前用户
        UserDTO user = UserHolder.getUser();
        Long userId = user.getId();

        String key = RedisConstants.FEED_KEY + userId;
        // 查看当前用户是否有收件箱 exist
        Boolean hasKey = stringRedisTemplate.hasKey(key);
        if (Boolean.FALSE.equals(hasKey)) {
            return Result.ok();
        }
        // 如果有,按照分数从大到小查询2条 偏移量第一次默认为0
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate
                .opsForZSet().reverseRangeByScoreWithScores(key, 0, max, offset, 2);

        // 再次判断收件箱
        if (typedTuples == null || typedTuples.isEmpty()) {
            return Result.ok();
        }
        // 遍历收件箱,解析数据
        ArrayList<Long> ids = new ArrayList<>(typedTuples.size());
        // 最小时间
        long minTime = 0;
        int os = 1;
        for (ZSetOperations.TypedTuple<String> typedTuple : typedTuples) {
            String idStr = typedTuple.getValue();
            assert idStr != null;
            ids.add(Long.parseLong(idStr));
            // 最后一个元素的时间
            long blogTime = typedTuple.getScore().longValue();
            if (minTime == blogTime) {
                os++;
            } else {
                minTime = blogTime;
                os = 1;
            }
        }

        String idStr = StrUtil.join(",", ids);

        // 根据笔记id查询笔记
        List<Blog> blogs = this.query().in("id", ids)
                .last("order by field(id," + idStr + ")").list();
        for (Blog blog : blogs) {
            queryBlogUser(blog);
            isBlogLiked(blog);
        }
        // 最小时间戳,offset 封装返回前端
        ScrollResult scrollResult = new ScrollResult();
        scrollResult.setList(blogs);
        scrollResult.setMinTime(minTime);
        scrollResult.setOffset(os);

        return Result.ok(scrollResult);
    }

    @Override
    public Result queryBlogById(Long id) {
        Blog blog = this.getById(id);
        if (blog == null) {
            return Result.fail("笔记不存在");
        }
        // 从缓存中获取最新的点赞信息
        Long likedCount = this.getLikeCountFromRedis(id);
        blog.setLiked(Math.toIntExact(likedCount));
        this.queryBlogUser(blog);
        this.isBlogLiked(blog);

        return Result.ok(blog);
    }

    /**
     * 判断当前用户是否点过赞了
     *
     * @param blog
     */
    private void isBlogLiked(Blog blog) {
        // 从redis中查询是否有数据,有则代表已经点赞 true 否则false
        String key = BLOG_LIKED_KEY + blog.getId();
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
