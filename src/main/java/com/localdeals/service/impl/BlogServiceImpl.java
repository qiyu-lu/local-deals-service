package com.localdeals.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.localdeals.dto.BlogDoc;
import com.localdeals.dto.BlogLikeCommandResult;
import com.localdeals.dto.Result;
import com.localdeals.dto.ScrollResult;
import com.localdeals.dto.UserDTO;
import com.localdeals.entity.Blog;
import com.localdeals.entity.Follow;
import com.localdeals.entity.User;
import com.localdeals.exception.ApiStatusException;
import com.localdeals.mapper.BlogMapper;
import com.localdeals.observability.LocalDealsMetrics;
import com.localdeals.service.IBlogService;
import com.localdeals.service.BlogLikeCommandService;
import com.localdeals.service.BlogHotRankReadResult;
import com.localdeals.service.BlogHotRankService;
import com.localdeals.service.BlogHotRankWarmupService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.localdeals.service.IFollowService;
import com.localdeals.service.IUserService;
import com.localdeals.service.UploadFileService;
import com.localdeals.config.BlogHotRankProperties;
import com.localdeals.config.BlogLikeProperties;
import com.localdeals.utils.SystemConstants;
import com.localdeals.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.elasticsearch.index.query.QueryBuilders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.core.ElasticsearchRestTemplate;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.query.NativeSearchQueryBuilder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.localdeals.utils.RedisConstants.FEED_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    private static final int FOLLOW_FEED_PAGE_SIZE = 2;

    @Resource
    private IUserService userService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IFollowService  followService;

    @Resource
    private UploadFileService uploadFileService;

    @Resource
    private BlogLikeCommandService blogLikeCommandService;

    @Resource
    private BlogHotRankService blogHotRankService;

    @Resource
    private BlogHotRankWarmupService blogHotRankWarmupService;

    @Resource
    private BlogHotRankProperties blogHotRankProperties;

    @Resource
    private BlogLikeProperties blogLikeProperties;

    @Resource
    private LocalDealsMetrics metrics;

    @Autowired
    private ElasticsearchRestTemplate esRestTemplate;

    @Override
    public Result queryHotBlog(Integer current) {
        if (current == null || current <= 0) {
            return Result.fail("页码必须为正数");
        }
        BlogHotRankReadResult rankResult = blogHotRankService.readPage(current);
        List<Blog> records = rankResult.isHit()
                ? loadRankedBlogs(rankResult.getBlogIds())
                : null;
        if (records == null) {
            blogHotRankWarmupService.triggerIfEnabled();
            long startedAt = System.nanoTime();
            try {
                records = query()
                        .orderByDesc("liked")
                        .orderByDesc("id")
                        .page(new Page<>(current, blogHotRankProperties.getPageSize(), false))
                        .getRecords();
            } finally {
                metrics.recordHotRankDbFallback(System.nanoTime() - startedAt);
            }
        }
        hydrateBlogList(records);
        return Result.ok(records);
    }

    /** Hydrates one bounded page with one user query and at most one like-state query. */
    private void hydrateBlogList(List<Blog> blogs) {
        if (blogs == null || blogs.isEmpty()) {
            return;
        }
        Set<Long> userIds = blogs.stream()
                .map(Blog::getUserId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, User> users = userIds.isEmpty()
                ? Collections.emptyMap()
                : userService.listByIds(userIds).stream()
                        .collect(Collectors.toMap(User::getId, user -> user));

        UserDTO currentUser = UserHolder.getUser();
        Set<Long> likedBlogIds = currentUser == null
                ? Collections.emptySet()
                : blogLikeCommandService.findLikedBlogIds(
                        currentUser.getId(),
                        blogs.stream().map(Blog::getId).collect(Collectors.toList()));
        for (Blog blog : blogs) {
            User author = users.get(blog.getUserId());
            if (author != null) {
                blog.setName(author.getNickName());
                blog.setIcon(author.getIcon());
            }
            if (currentUser != null) {
                blog.setIsLike(likedBlogIds.contains(blog.getId()));
            }
        }
    }

    /** Returns null when a stale rank contains a blog which no longer exists. */
    private List<Blog> loadRankedBlogs(List<Long> blogIds) {
        if (blogIds.isEmpty()) {
            return Collections.emptyList();
        }
        Map<Long, Blog> byId = listByIds(blogIds).stream()
                .collect(Collectors.toMap(Blog::getId, blog -> blog, (left, right) -> left,
                        LinkedHashMap::new));
        if (byId.size() != blogIds.size()) {
            return null;
        }
        List<Blog> ordered = new ArrayList<>(blogIds.size());
        for (Long blogId : blogIds) {
            Blog blog = byId.get(blogId);
            if (blog == null) {
                return null;
            }
            ordered.add(blog);
        }
        return ordered;
    }

    @Override
    public Result queryBlogById(Long id) {
        //查询blog
        Blog blog = getById(id);
        if(blog == null){return Result.fail("笔记不存在!");}
        //查询Blog有关的用户
        queryBlogUser(blog);
        //查询blog是否被点赞
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    private void isBlogLiked(Blog blog) {
        UserDTO user = UserHolder.getUser();
        if(user == null){
            return;
        }
        //1.获取登录用户
        Long userId = user.getId();
        blog.setIsLike(blogLikeCommandService.isLiked(blog.getId(), userId));
    }

    @Override
    public Result setBlogLiked(Long id, boolean liked) {
        LocalDealsMetrics.LikeOperation operation = liked
                ? LocalDealsMetrics.LikeOperation.LIKE
                : LocalDealsMetrics.LikeOperation.UNLIKE;
        try {
            if (!blogLikeProperties.isWriteEnabled()) {
                throw new ApiStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE, "点赞功能维护中，请稍后重试");
            }
            Long userId = UserHolder.getUser().getId();
            BlogLikeCommandResult commandResult = blogLikeCommandService.setLiked(id, userId, liked);
            if (commandResult.getOutcome() == BlogLikeCommandResult.Outcome.NOT_FOUND) {
                metrics.recordLike(operation, LocalDealsMetrics.LikeResult.NOT_FOUND);
                throw new ApiStatusException(HttpStatus.NOT_FOUND, "笔记不存在!");
            }
            metrics.recordLike(operation, commandResult.isChanged()
                    ? LocalDealsMetrics.LikeResult.CHANGED
                    : LocalDealsMetrics.LikeResult.UNCHANGED);
            return Result.ok(commandResult);
        } catch (RuntimeException failure) {
            if (!(failure instanceof ApiStatusException) ||
                    ((ApiStatusException) failure).getStatus() != HttpStatus.NOT_FOUND) {
                metrics.recordLike(operation, LocalDealsMetrics.LikeResult.FAILURE);
            }
            throw failure;
        }
    }

    @Override
    public Result queryBlogLikes(Long id) {
        List<Long> ids = blogLikeCommandService.findTopFiveUserIds(id);
        if (ids.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        String idStr = StrUtil.join("," , ids);
        List<UserDTO> userDTOS = userService.query()
                .in("id", ids).last("ORDER BY FIELD(id, " + idStr+ ")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(userDTOS);
    }

    @Override
    public Result queryBlogsByUserId(Long userId, Integer current) {
        requirePositivePageAndId(userId, current, "userId");
        List<Blog> records = query()
                .eq("user_id", userId)
                .orderByDesc("id")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE, false))
                .getRecords();
        hydrateBlogList(records);
        return Result.ok(records);
    }

    @Override
    public Result queryBlogsByShopId(Long shopId, Integer current) {
        requirePositivePageAndId(shopId, current, "shopId");
        List<Blog> records = query()
                .eq("shop_id", shopId)
                .orderByDesc("liked")
                .orderByDesc("id")
                .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE, false))
                .getRecords();
        hydrateBlogList(records);
        return Result.ok(records);
    }

    private static void requirePositivePageAndId(Long id, Integer current, String idName) {
        if (id == null || id <= 0L) {
            throw new IllegalArgumentException(idName + " must be positive");
        }
        if (current == null || current <= 0) {
            throw new IllegalArgumentException("current must be positive");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        List<String> imagePaths;
        try {
            imagePaths = uploadFileService.validateTemporaryImages(blog.getImages(), user.getId());
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        }
        blog.setUserId(user.getId());
        // 保存探店博文
        boolean success = save(blog);
        if(!success){
            return Result.fail("新增笔记失败!");
        }
        uploadFileService.markPublished(imagePaths, user.getId(), blog.getId());
        List<Long> followerIds = followService.query()
                .eq("follow_user_id", user.getId())
                .list()
                .stream()
                .map(Follow::getUserId)
                .collect(Collectors.toList());
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Blog creation requires an active transaction synchronization");
        }
        final Long committedBlogId = blog.getId();
        final long publishedAt = System.currentTimeMillis();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                blogHotRankService.addNewBlogAfterCommit(committedBlogId);
                for (Long followerId : followerIds) {
                    try {
                        stringRedisTemplate.opsForZSet().add(
                                FEED_KEY + followerId,
                                committedBlogId.toString(),
                                publishedAt);
                    } catch (RuntimeException e) {
                        log.error("Unable to publish committed blog to follower feed. blogId={}, followerId={}",
                                committedBlogId, followerId, e);
                    }
                }
            }
        });
        // 返回id
        return Result.ok(blog.getId());
    }

    //查询收件箱的所有笔记实现滚动分页
    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        //1.获取当前用户
        Long userId = UserHolder.getUser().getId();
        //2. 查询收件箱
        String key = FEED_KEY + userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, FOLLOW_FEED_PAGE_SIZE);
        if(typedTuples == null || typedTuples.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        //3.解析数据 blogId，score(时间戳)、offset
        List<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime = 0;
        int os = 1;
        for(ZSetOperations.TypedTuple<String> typedTuple : typedTuples){
            ids.add(Long.valueOf(typedTuple.getValue()));

            long time = typedTuple.getScore().longValue();
            if(time == minTime) os++;
            else {
                minTime = time;
                os = 1;
            }
        }
        //4.根据id查询blog
        String idStr = StrUtil.join("," , ids);
        List<Blog> blogs = query().in("id", ids)
                .last("ORDER BY FIELD(id, " + idStr + ")").list();

        for(Blog blog : blogs){
            queryBlogUser(blog);
            isBlogLiked(blog);
        }

        //5.封装并返回
        ScrollResult r = new ScrollResult();
        r.setList(blogs);
        r.setOffset(os);
        r.setMinTime(minTime);
        return Result.ok(r);
    }

    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        if (user == null) { return; }
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }

    @Override
    public Result searchBlogs(String keyword, Integer current) {
        NativeSearchQueryBuilder queryBuilder = new NativeSearchQueryBuilder();
        if (StrUtil.isNotBlank(keyword)) {
            queryBuilder.withQuery(QueryBuilders.multiMatchQuery(keyword, "title", "content"));
        } else {
            queryBuilder.withQuery(QueryBuilders.matchAllQuery());
        }
        queryBuilder.withPageable(PageRequest.of(current - 1, SystemConstants.DEFAULT_PAGE_SIZE));

        SearchHits<BlogDoc> hits = esRestTemplate.search(queryBuilder.build(), BlogDoc.class,
                IndexCoordinates.of("blog_index"));
        List<BlogDoc> docs = hits.getSearchHits().stream()
                .map(SearchHit::getContent).collect(Collectors.toList());
        return Result.ok(docs);
    }
}
