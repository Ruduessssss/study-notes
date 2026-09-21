package com.hmdp;

import com.hmdp.service.IShopService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    public IShopService shopService;

    @Test
    public void testSaveHotShop()
    {
        shopService.saveShop2Redis(1L,1L, TimeUnit.SECONDS);
    }

}
