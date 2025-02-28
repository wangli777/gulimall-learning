package com.wangli.gulimall.product.service.impl;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.wangli.common.utils.PageUtils;
import com.wangli.common.utils.Query;
import com.wangli.gulimall.product.dao.CategoryDao;
import com.wangli.gulimall.product.entity.CategoryEntity;
import com.wangli.gulimall.product.service.CategoryBrandRelationService;
import com.wangli.gulimall.product.service.CategoryService;
import com.wangli.gulimall.product.vo.web.Catalog2Vo;
import org.redisson.api.RLock;
import org.redisson.api.RReadWriteLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;


@Service("categoryService")
public class CategoryServiceImpl extends ServiceImpl<CategoryDao, CategoryEntity> implements CategoryService {
    @Autowired
    CategoryBrandRelationService categoryBrandRelationService;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Autowired
    RedissonClient redisson;

    @Override
    public PageUtils queryPage(Map<String, Object> params) {
        IPage<CategoryEntity> page = this.page(
                new Query<CategoryEntity>().getPage(params),
                new QueryWrapper<CategoryEntity>()
        );

        return new PageUtils(page);
    }

    @Override
    public void removeMenusByIds(List<Long> ids) {
        // TODO: 2021/6/4 检查菜单是否可以删除
        baseMapper.deleteBatchIds(ids);
    }

    @Override
    public Long[] findCatelogPath(Long catelogId) {
        List<Long> paths = new ArrayList<>();
        List<Long> parentPath = getParentPath(catelogId, paths);
        Collections.reverse(parentPath);
        return parentPath.toArray(new Long[parentPath.size()]);
    }

    /**
     * 级联更新所有关联的数据
     * 更新时删除缓存
     *
     * @param category
     */
    @Caching(evict = {
            @CacheEvict(value = {"category"}, key = "'listLevel1Cates'"),
            @CacheEvict(value = {"category"}, key = "'getCatalogJson'")
    })
//    @CacheEvict(value = {"category"}, allEntries = true)
    @Transactional
    @Override
    public void updateCascade(CategoryEntity category) {
        this.updateById(category);
        categoryBrandRelationService.updateCategory(category.getCatId(), category.getName());
    }

    //代表当前方法的结果需要缓存，如果缓存中有，不需要执行方法，如果缓存中没有，则需要执行方法，并将方法执行结果放入缓存
    //每一个需要缓存的数据，我们都需要指定一个名字用来分区。【缓存的分区（一般按照业务类型分）】
//    @Cacheable(value = {"category"}, key = "'level1Cates'")
    @Cacheable(value = {"category"}, key = "#root.methodName", sync = true)
    @Override
    public List<CategoryEntity> listLevel1Cates() {
        log.debug("执行listLevel1Cates...");
        return this.baseMapper.selectList(new QueryWrapper<CategoryEntity>().eq("parent_cid", 0));
    }

    /**
     * 获取分类数据 使用Spring-Cache纯注解方式
     * 功能同 getCatalogJsonByHand()
     *
     * @return
     */
    @Cacheable(value = {"category"}, key = "#root.methodName", sync = true)
    @Override
    public Map<String, List<Catalog2Vo>> getCatalogJson() {
        log.warn("真正查询数据库...");
        // 性能优化：将数据库的多次查询变为一次
        List<CategoryEntity> selectList = this.baseMapper.selectList(null);
        //1、1）查出所有一级分类
        List<CategoryEntity> level1Cates = getParentCid(selectList, 0L);
        Map<String, List<Catalog2Vo>> listMap = level1Cates.stream().collect(Collectors.toMap(k -> k.getCatId().toString(), v -> {
            //二级分类
            List<CategoryEntity> level2Cates = getParentCid(selectList, v.getCatId());
            List<Catalog2Vo> catalogs2Vos = new ArrayList<>();
            if (level2Cates != null) {
                //将二级分类转为 Catalog2Vo
                catalogs2Vos = level2Cates.stream().map(l2 -> {
                    Catalog2Vo catalog2Vo = new Catalog2Vo(v.getCatId().toString(), null, l2.getCatId().toString(), l2.getName());
                    //查出三级分类
                    List<CategoryEntity> level3 = getParentCid(selectList, l2.getCatId());
                    if (level3 != null) {
                        List<Catalog2Vo.Category3Vo> category3Vos = level3.stream().map(l3 -> {
                            Catalog2Vo.Category3Vo category3Vo = new Catalog2Vo.Category3Vo(l2.getCatId().toString(), l3.getCatId().toString(), l3.getName());
                            return category3Vo;
                        }).collect(Collectors.toList());
                        catalog2Vo.setCatalog3List(category3Vos);
                    }
                    return catalog2Vo;
                }).collect(Collectors.toList());
            }

            return catalogs2Vos;
        }));

        return listMap;
    }

    /**
     * 获取分类数据 使用手动编码方式
     * 功能同 getCatalogJson()
     *
     * @return
     */
//    @Override
    public Map<String, List<Catalog2Vo>> getCatalogJsonByHand() {
        //1、缓存空数据（缓存穿透）
        //2、设置随机过期时间（缓存雪崩）
        //3、查询数据库时加锁（缓存击穿）

        //先从redis缓存中获取
        String catalogJson = redisTemplate.opsForValue().get("catalogJson");
        if (StrUtil.isBlank(catalogJson)) {
            log.warn("缓存中没有...准备查询数据库...");
            //缓存中数据不存在，查db,查出来的数据存redis
//            Map<String, List<Catalog2Vo>> catalogJsonFromDB = this.getCatalogJsonFromDBWithLocalLock(); //本地锁方式
//            Map<String, List<Catalog2Vo>> catalogJsonFromDB = this.getCatalogJsonWithRedisLock(); //redis基础版
            Map<String, List<Catalog2Vo>> catalogJsonFromDB = this.getCatalogJsonWithRedisson(); //redisson方式

            return catalogJsonFromDB;
        }
        log.warn("缓存命中...");

        return JSON.parseObject(catalogJson, new TypeReference<Map<String, List<Catalog2Vo>>>() {
        });
    }

    /**
     * 从数据库查询并封装数据 使用redisson分布式锁
     * 缓存里的数据如何和数据库的数据保持一致？
     * 缓存数据一致性
     * 1)、双写模式
     * 2)、失效模式
     *
     * @return
     */
    private Map<String, List<Catalog2Vo>> getCatalogJsonWithRedisson() {

        //1、获取redisson读写锁
        RReadWriteLock readWriteLock = redisson.getReadWriteLock("catalogJson-lock");
        //2、获取读锁
        RLock rLock = readWriteLock.readLock();
        Map<String, List<Catalog2Vo>> dataFromDb;
        try {
            //加读锁，可以并发读，但是 读+写 或者 写+读 都是互斥的，需要阻塞等待
            rLock.lock();
            log.debug("获取redisson读锁加锁...");

            //加锁成功...执行业务
            dataFromDb = getDataFromDb();
        } finally {
            // 解锁
            rLock.unlock();
            log.debug("redisson读锁解锁...");
        }
        return dataFromDb;
    }

    /**
     * 从数据库查询并封装数据 使用分布式锁
     *
     * @return
     */
    private Map<String, List<Catalog2Vo>> getCatalogJsonWithRedisLock() {

        //1、占分布式锁。去redis占坑      设置过期时间必须和加锁是同步的，保证原子性（避免死锁）
        String uuid = UUID.randomUUID().toString();
        Boolean lock = redisTemplate.opsForValue().setIfAbsent("lock", uuid, 300, TimeUnit.SECONDS);
        assert lock != null;
        if (lock) {
            log.debug("获取分布式锁成功...");
            Map<String, List<Catalog2Vo>> dataFromDb;
            try {
                //加锁成功...执行业务
                dataFromDb = getDataFromDb();
            } finally {
                // lua 脚本解锁
                String script = "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end";
                // 删除锁
                redisTemplate.execute(new DefaultRedisScript<>(script, Long.class), Collections.singletonList("lock"), uuid);
            }
            //先去redis查询下保证当前的锁是自己的
            //获取值对比，对比成功删除=原子性 lua脚本解锁
            // String lockValue = stringRedisTemplate.opsForValue().get("lock");
            // if (uuid.equals(lockValue)) {
            //     //删除我自己的锁
            //     stringRedisTemplate.delete("lock");
            // }
            return dataFromDb;
        } else {
            log.debug("获取分布式锁失败...等待重试...");
            //加锁失败...重试机制
            //休眠一百毫秒
            try {
                TimeUnit.MILLISECONDS.sleep(100);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            return getCatalogJsonWithRedisLock();     //自旋的方式
        }
    }

    private Map<String, List<Catalog2Vo>> getCatalogJsonFromDBWithLocalLock() {
        //查询db加锁
        synchronized (this) {
            return getDataFromDb();
        }
    }

    private Map<String, List<Catalog2Vo>> getDataFromDb() {
        //得到锁以后，先判断redis中是否存在（重要，没这一步会导致并发时多次查询数据库）
        String catalogJson = redisTemplate.opsForValue().get("catalogJson");
        if (StrUtil.isNotBlank(catalogJson)) {
            //缓存不为空，直接返回
            return JSON.parseObject(catalogJson, new TypeReference<Map<String, List<Catalog2Vo>>>() {
            });
        }
        log.warn("真正查询数据库...");
        // 性能优化：将数据库的多次查询变为一次
        List<CategoryEntity> selectList = this.baseMapper.selectList(null);
        //1、1）查出所有一级分类
        List<CategoryEntity> level1Cates = getParentCid(selectList, 0L);
        Map<String, List<Catalog2Vo>> listMap = level1Cates.stream().collect(Collectors.toMap(k -> k.getCatId().toString(), v -> {
            //二级分类
            List<CategoryEntity> level2Cates = getParentCid(selectList, v.getCatId());
            List<Catalog2Vo> catalogs2Vos = new ArrayList<>();
            if (level2Cates != null) {
                //将二级分类转为 Catalog2Vo
                catalogs2Vos = level2Cates.stream().map(l2 -> {
                    Catalog2Vo catalog2Vo = new Catalog2Vo(v.getCatId().toString(), null, l2.getCatId().toString(), l2.getName());
                    //查出三级分类
                    List<CategoryEntity> level3 = getParentCid(selectList, l2.getCatId());
                    if (level3 != null) {
                        List<Catalog2Vo.Category3Vo> category3Vos = level3.stream().map(l3 -> {
                            Catalog2Vo.Category3Vo category3Vo = new Catalog2Vo.Category3Vo(l2.getCatId().toString(), l3.getCatId().toString(), l3.getName());
                            return category3Vo;
                        }).collect(Collectors.toList());
                        catalog2Vo.setCatalog3List(category3Vos);
                    }
                    return catalog2Vo;
                }).collect(Collectors.toList());
            }

            return catalogs2Vos;
        }));

        //将数据存到redis中
        redisTemplate.opsForValue().set("catalogJson", JSON.toJSONString(listMap));
        return listMap;
    }

    //    private Map<String, List<Catalog2Vo>> getCatalogJsonFromDB() {
//        log.warn("真正查询数据库...");
//        // 性能优化：将数据库的多次查询变为一次
//        List<CategoryEntity> selectList = this.baseMapper.selectList(null);
//        //1、1）查出所有一级分类
//        List<CategoryEntity> level1Cates = getParentCid(selectList, 0L);
//        Map<String, List<Catalog2Vo>> listMap = level1Cates.stream().collect(Collectors.toMap(k -> k.getCatId().toString(), v -> {
//            //二级分类
//            List<CategoryEntity> level2Cates = getParentCid(selectList, v.getCatId());
//            List<Catalog2Vo> catalogs2Vos = null;
//            if (level2Cates != null) {
//                //将二级分类转为 Catalog2Vo
//                catalogs2Vos = level2Cates.stream().map(l2 -> {
//                    Catalog2Vo catalog2Vo = new Catalog2Vo(v.getCatId().toString(), null, l2.getCatId().toString(), l2.getName());
//                    //查出三级分类
//                    List<CategoryEntity> level3 = getParentCid(selectList, l2.getCatId());
//                    if (level3 != null) {
//                        List<Catalog2Vo.Category3Vo> category3Vos = level3.stream().map(l3 -> {
//                            Catalog2Vo.Category3Vo category3Vo = new Catalog2Vo.Category3Vo(l2.getCatId().toString(), l3.getCatId().toString(), l3.getName());
//                            return category3Vo;
//                        }).collect(Collectors.toList());
//                        catalog2Vo.setCatalog3List(category3Vos);
//                    }
//                    return catalog2Vo;
//                }).collect(Collectors.toList());
//            }
//
//            return catalogs2Vos;
//        }));
//        return listMap;
//    }

    private List<CategoryEntity> getParentCid(List<CategoryEntity> selectList, Long parentCid) {
        return selectList.stream().filter(item -> item.getParentCid().equals(parentCid)).collect(Collectors.toList());
    }

    private List<Long> getParentPath(Long catelogId, List<Long> paths) {
        CategoryEntity byId = this.getById(catelogId);
        if (byId == null) {
            return paths;
        }
        //收集当前节点
        paths.add(byId.getCatId());
        if (byId.getParentCid() != 0) {
            getParentPath(byId.getParentCid(), paths);
        }
        return paths;
    }

    /**
     * 查找所有商品目录分类，父子结构
     *
     * @return
     */
    @Override
    public List<CategoryEntity> listWithTree() {
        List<CategoryEntity> categoryEntities = baseMapper.selectList(null);
        return categoryEntities.stream().filter(entity -> entity.getParentCid() == 0)
                .peek(e -> e.setChildren(this.getChildrens(e, categoryEntities)))
                .sorted(Comparator.comparingInt(o -> (o.getSort() == null ? 0 : o.getSort())))
                .collect(Collectors.toList());
    }

    /**
     * 设置当前类名的子类名，递归设置
     *
     * @param current
     * @param all
     * @return
     */
    private List<CategoryEntity> getChildrens(CategoryEntity current, List<CategoryEntity> all) {
        return all.stream().filter(e -> current.getCatId().equals(e.getParentCid()))
                .peek(e -> e.setChildren(getChildrens(e, all)))
                .sorted(Comparator.comparingInt(o -> (o.getSort() == null ? 0 : o.getSort())))
                .collect(Collectors.toList());
    }

}