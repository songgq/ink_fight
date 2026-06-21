package cn.iocoder.yudao.module.game.controller.app;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import cn.iocoder.yudao.module.game.service.GameAppService;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.annotation.security.PermitAll;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@RestController
@RequestMapping("/game")
@PermitAll
@TenantIgnore
public class GameAppController {

    @Resource
    private GameAppService gameAppService;

    @PostMapping("/auth/register")
    public CommonResult<Map<String, Object>> register(@RequestBody Map<String, Object> req) {
        return success(gameAppService.register(req));
    }

    @PostMapping("/auth/login")
    public CommonResult<Map<String, Object>> login(@RequestBody Map<String, Object> req) {
        return success(gameAppService.login(req));
    }

    @GetMapping("/player/me")
    public CommonResult<Map<String, Object>> me(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return success(gameAppService.me(authorization));
    }

    @GetMapping("/cards")
    public CommonResult<Map<String, Object>> cards(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return success(gameAppService.cards(authorization));
    }

    @PostMapping("/cards/{cardCode}/upgrade")
    public CommonResult<Map<String, Object>> upgrade(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                     @PathVariable String cardCode) {
        return success(gameAppService.upgrade(authorization, cardCode));
    }

    @GetMapping("/journey")
    public CommonResult<Map<String, Object>> journey(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return success(gameAppService.journey(authorization));
    }

    @PostMapping("/journey/{stageNo}/start")
    public CommonResult<Map<String, Object>> startJourney(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                          @PathVariable Integer stageNo) {
        return success(gameAppService.startJourney(authorization, stageNo));
    }

    @GetMapping("/battles/{battleNo}")
    public CommonResult<Map<String, Object>> battle(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                    @PathVariable String battleNo) {
        return success(gameAppService.battle(authorization, battleNo));
    }

    @PostMapping("/battles/{battleNo}/commands")
    public CommonResult<Map<String, Object>> command(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                     @PathVariable String battleNo,
                                                     @RequestBody Map<String, Object> req) {
        return success(gameAppService.command(authorization, battleNo, req));
    }

    @PostMapping("/battles/{battleNo}/tick")
    public CommonResult<Map<String, Object>> tick(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                  @PathVariable String battleNo) {
        return success(gameAppService.tick(authorization, battleNo));
    }

    @PostMapping("/battles/{battleNo}/settle")
    public CommonResult<Map<String, Object>> settle(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                    @PathVariable String battleNo) {
        return success(gameAppService.settle(authorization, battleNo));
    }

    @PostMapping("/battles/{battleNo}/surrender")
    public CommonResult<Map<String, Object>> surrender(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                       @PathVariable String battleNo) {
        return success(gameAppService.surrender(authorization, battleNo));
    }
}
