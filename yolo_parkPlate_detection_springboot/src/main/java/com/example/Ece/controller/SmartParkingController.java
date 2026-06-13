package com.example.Ece.controller;

import com.example.Ece.common.Result;
import com.example.Ece.service.SmartParkingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.Map;

@RestController
@RequestMapping("/smart")
public class SmartParkingController {

    @Resource
    private SmartParkingService smartParkingService;

    @GetMapping("/summary")
    public Result<?> summary() {
        return Result.success(smartParkingService.summary());
    }

    @GetMapping("/recommend")
    public Result<?> recommend(@RequestParam(defaultValue = "") String plateNumber) {
        return Result.success(smartParkingService.recommend(plateNumber));
    }

    @GetMapping("/forecast")
    public Result<?> forecast(@RequestParam(defaultValue = "3") Integer hours,
                              @RequestParam(defaultValue = "") String parkArea) {
        return Result.success(smartParkingService.forecast(hours == null ? 3 : hours, parkArea));
    }

    @GetMapping("/alerts")
    public Result<?> alerts() {
        return Result.success(smartParkingService.alerts());
    }

    @PostMapping("/alerts/{id}/resolve")
    public Result<?> resolveAlert(@PathVariable Integer id) {
        return Result.success("预警为数据库实时计算结果，刷新后会根据最新停车数据自动消除。处理ID：" + id);
    }

    @PostMapping("/billing/estimate")
    public Result<?> estimateBilling(@RequestBody Map<String, Object> params) {
        String parkArea = params.get("parkArea") == null ? "" : params.get("parkArea").toString();
        Double durationHours = null;
        if (params.get("durationHours") != null) {
            durationHours = Double.parseDouble(params.get("durationHours").toString());
        }
        return Result.success(smartParkingService.estimateBilling(parkArea, durationHours));
    }

    @PostMapping("/chat")
    public Result<?> chat(@RequestBody Map<String, Object> params) {
        String question = params.get("question") == null ? "" : params.get("question").toString();
        return Result.success(smartParkingService.answer(question));
    }
}