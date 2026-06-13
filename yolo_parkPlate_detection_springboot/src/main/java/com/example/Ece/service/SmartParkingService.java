package com.example.Ece.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.Ece.entity.Area;
import com.example.Ece.entity.PlateRecords;
import com.example.Ece.mapper.AreaMapper;
import com.example.Ece.mapper.PlateRecordsMapper;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class SmartParkingService {

    @Resource
    private AreaMapper areaMapper;
    @Resource
    private PlateRecordsMapper plateRecordsMapper;

    public Map<String, Object> summary() {
        List<Area> areas = areaMapper.selectList(Wrappers.<Area>lambdaQuery());
        LocalDate today = LocalDate.now();
        LocalDateTime dayStart = today.atStartOfDay();
        LocalDateTime dayEnd = today.atTime(23, 59, 59);

        int totalAreas = areas.size();
        int totalSpaces = 0;
        int usedSpaces = 0;
        int unusedSpaces = 0;
        for (Area area : areas) {
            if (!isBusinessAreaName(area.getParkArea())) {
                continue;
            }
            int used = safeInteger(area.getUsed());
            int unused = safeInteger(area.getUnused());
            usedSpaces += used;
            unusedSpaces += unused;
            totalSpaces += used + unused;
        }

        List<PlateRecords> activeRecords = plateRecordsMapper.selectList(Wrappers.<PlateRecords>lambdaQuery()
                .isNull(PlateRecords::getEndTime));
        List<PlateRecords> todayFinishedRecords = plateRecordsMapper.selectList(Wrappers.<PlateRecords>lambdaQuery()
                .isNotNull(PlateRecords::getEndTime)
                .between(PlateRecords::getEndTime, dayStart, dayEnd));

        double todayIncome = 0;
        double durationTotal = 0;
        for (PlateRecords record : todayFinishedRecords) {
            todayIncome += record.getPrice();
            durationTotal += record.getDuration();
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("totalAreas", totalAreas);
        data.put("totalSpaces", totalSpaces);
        data.put("usedSpaces", usedSpaces);
        data.put("unusedSpaces", unusedSpaces);
        data.put("currentVehicles", activeRecords.size());
        data.put("occupancyRate", rate(usedSpaces, totalSpaces));
        data.put("todayIncome", round2(todayIncome));
        data.put("todayFinishedCount", todayFinishedRecords.size());
        data.put("averageDuration", todayFinishedRecords.isEmpty() ? 0 : round2(durationTotal / todayFinishedRecords.size()));
        data.put("turnoverRate", totalSpaces == 0 ? 0 : round2(todayFinishedRecords.size() * 100.0 / totalSpaces));

        List<Map<String, Object>> areaStats = areaStats(areas);
        List<Map<String, Object>> forecast = forecast(3, "");
        List<Map<String, Object>> alerts = alerts();
        Map<String, Object> recommendation = recommend("");
        data.put("areaStats", areaStats);
        data.put("forecast", forecast);
        data.put("alerts", alerts);
        data.put("recommendation", recommendation);
        data.put("explanations", explanations(data, areaStats, forecast, alerts, recommendation));
        return data;
    }

    public Map<String, Object> recommend(String plateNumber) {
        List<Area> areas = areaMapper.selectList(Wrappers.<Area>lambdaQuery());
        return buildRecommendation(areas, plateNumber);
    }

    public List<Map<String, Object>> forecast(int hours, String parkArea) {
        int normalizedHours = Math.max(1, Math.min(hours, 6));
        List<Area> areas = getAreas(parkArea);
        int totalSpaces = 0;
        int projectedUnused = 0;
        for (Area area : areas) {
            totalSpaces += safeInteger(area.getUsed()) + safeInteger(area.getUnused());
            projectedUnused += safeInteger(area.getUnused());
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime historyStart = now.minusDays(14);
        LambdaQueryWrapper<PlateRecords> wrapper = Wrappers.<PlateRecords>lambdaQuery()
                .ge(PlateRecords::getStartTime, historyStart);
        if (StrUtil.isNotBlank(parkArea)) {
            wrapper.eq(PlateRecords::getParkArea, parkArea.trim());
        }
        List<PlateRecords> history = plateRecordsMapper.selectList(wrapper);
        if (history.isEmpty()) {
            return new ArrayList<>();
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = 1; i <= normalizedHours; i++) {
            LocalDateTime forecastTime = now.plusHours(i);
            int hour = forecastTime.getHour();
            int expectedIn = averageCountByHour(history, hour, true);
            int expectedOut = averageCountByHour(history, hour, false);
            projectedUnused = clamp(projectedUnused - expectedIn + expectedOut, 0, totalSpaces);
            double occupancyRate = totalSpaces == 0 ? 0 : round2((totalSpaces - projectedUnused) * 100.0 / totalSpaces);

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("time", forecastTime.format(DateTimeFormatter.ofPattern("HH:00")));
            item.put("forecastTime", forecastTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:00")));
            item.put("expectedIn", expectedIn);
            item.put("expectedOut", expectedOut);
            item.put("expectedUnused", projectedUnused);
            item.put("occupancyRate", occupancyRate);
            item.put("pressureLevel", pressureLevel(occupancyRate));
            item.put("explain", "仅基于数据库 platerecords 近14天同小时真实入场/出场记录聚合，未使用模拟车辆。");
            result.add(item);
        }
        return result;
    }

    public List<Map<String, Object>> alerts() {
        List<Map<String, Object>> result = new ArrayList<>();
        int alertId = 1;
        List<Area> areas = areaMapper.selectList(Wrappers.<Area>lambdaQuery());
        for (Area area : areas) {
            if (!isBusinessAreaName(area.getParkArea())) {
                continue;
            }
            int used = safeInteger(area.getUsed());
            int unused = safeInteger(area.getUnused());
            int total = used + unused;
            double occupancyRate = rate(used, total);
            if (used < 0 || unused < 0) {
                result.add(alert(alertId++, "DATA_ABNORMAL", "high", null, area.getParkArea(), "区域车位数量出现负数，请立即核对。"));
            } else if (total > 0 && unused == 0) {
                result.add(alert(alertId++, "FULL_AREA", "high", null, area.getParkArea(), area.getParkArea() + "已满位，建议引导车辆到其它区域。"));
            } else if (occupancyRate >= 85) {
                result.add(alert(alertId++, "HIGH_OCCUPANCY", "warning", null, area.getParkArea(), area.getParkArea() + "占用率" + occupancyRate + "%超过85%，可能进入高峰。"));
            }
        }

        List<PlateRecords> activeRecords = plateRecordsMapper.selectList(Wrappers.<PlateRecords>lambdaQuery()
                .isNull(PlateRecords::getEndTime));
        Map<String, Integer> activePlateCount = new HashMap<>();
        LocalDateTime now = LocalDateTime.now();
        for (PlateRecords record : activeRecords) {
            if (!isBusinessAreaName(record.getParkArea()) || !isValidPlateNumber(record.getPlateNumber())) {
                continue;
            }
            if (record.getStartTime() != null && record.getStartTime().isBefore(now.minusDays(7))) {
                continue;
            }
            activePlateCount.put(record.getPlateNumber(), activePlateCount.getOrDefault(record.getPlateNumber(), 0) + 1);
            if (record.getStartTime() != null && Duration.between(record.getStartTime(), now).toHours() >= 24) {
                result.add(alert(alertId++, "LONG_STAY", "warning", record.getPlateNumber(), record.getParkArea(),
                        record.getPlateNumber() + "已连续停车超过24小时，请关注是否异常占用。"));
            }
        }
        for (Map.Entry<String, Integer> entry : activePlateCount.entrySet()) {
            if (entry.getValue() > 1) {
                result.add(alert(alertId++, "DUPLICATE_ACTIVE", "high", entry.getKey(), null,
                        entry.getKey() + "存在多条未出场记录，疑似重复入场或套牌。"));
            }
        }

        return result;
    }

    public Map<String, Object> estimateBilling(String parkArea, Double durationHours) {
        Area area = findArea(parkArea);
        double duration = durationHours == null ? 2.0 : Math.max(0, durationHours);
        if (area == null) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("available", false);
            data.put("message", "停车区域不存在，无法估算费用。");
            return data;
        }
        return buildBilling(area, duration, LocalDateTime.now());
    }

    public double calculateDynamicFee(Area area, double durationHours, LocalDateTime endTime) {
        Map<String, Object> billing = buildBilling(area, durationHours, endTime == null ? LocalDateTime.now() : endTime);
        return ((Number) billing.get("finalFee")).doubleValue();
    }

    public Map<String, Object> answer(String question) {
        String q = question == null ? "" : question.trim();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("question", q);

        if (containsAny(q, "推荐", "停哪", "哪里停车", "最优")) {
            Map<String, Object> recommendation = recommend("");
            result.put("answer", "推荐停入" + recommendation.get("parkArea") + "。原因：" + recommendation.get("reason"));
            result.put("data", recommendation);
            return result;
        }
        if (containsAny(q, "空余", "还有多少", "剩余", "车位")) {
            Map<String, Object> summary = summary();
            Area matchedArea = matchArea(q);
            if (matchedArea != null) {
                result.put("answer", matchedArea.getParkArea() + "当前空余" + safeInteger(matchedArea.getUnused()) + "个、占用" + safeInteger(matchedArea.getUsed()) + "个，占用率" + rate(safeInteger(matchedArea.getUsed()), safeInteger(matchedArea.getUsed()) + safeInteger(matchedArea.getUnused())) + "%。");
                result.put("data", matchedArea);
            } else {
                result.put("answer", "全场总车位" + summary.get("totalSpaces") + "个，空余" + summary.get("unusedSpaces") + "个，占用率" + summary.get("occupancyRate") + "%。");
                result.put("data", summary);
            }
            return result;
        }
        if (containsAny(q, "预警", "异常", "满", "高占用", "超过24")) {
            List<Map<String, Object>> alerts = alerts();
            if (alerts.isEmpty()) {
                result.put("answer", "暂无真实预警：数据库中没有满足高占用、满位、长时停车或重复未出场条件的记录。");
            } else {
                result.put("answer", "当前真实预警" + alerts.size() + "条。" + alerts.get(0).get("message"));
            }
            result.put("data", alerts);
            return result;
        }
        if (containsAny(q, "预测", "高峰", "未来", "压力")) {
            List<Map<String, Object>> forecast = forecast(3, "");
            if (forecast.isEmpty()) {
                result.put("answer", "暂无真实历史停车记录，数据不足，暂不预测。");
            } else {
                result.put("answer", "未来3小时压力预测已生成，最高压力为" + highestPressure(forecast) + "。预测依据：数据库 platerecords 近14天同小时真实入场/出场记录聚合。");
            }
            result.put("data", forecast);
            return result;
        }
        if (containsAny(q, "计费", "收费", "价格", "费用", "多少钱")) {
            Map<String, Object> recommendation = recommend("");
            Map<String, Object> billing = estimateBilling((String) recommendation.get("parkArea"), 2.0);
            if (Boolean.TRUE.equals(billing.get("available"))) {
                result.put("answer", "动态计费规则：前15分钟免费；按区域单价计费；早晚高峰加价20%；占用率超过85%加价15%，低于40%九折；单日封顶120元。以当前真实可用推荐区域停车2小时估算约" + billing.get("finalFee") + "元。");
            } else {
                result.put("answer", "动态计费规则：前15分钟免费；按区域单价计费；早晚高峰加价20%；占用率超过85%加价15%，低于40%九折；单日封顶120元。当前没有真实可用停车区域，暂不估算具体费用。");
            }
            result.put("data", billing);
            return result;
        }
        if (containsAny(q, "收入", "今日收入", "营业额")) {
            Map<String, Object> summary = summary();
            result.put("answer", "今日已结算收入约" + summary.get("todayIncome") + "元，今日完成出场" + summary.get("todayFinishedCount") + "次，平均停车" + summary.get("averageDuration") + "小时。");
            result.put("data", summary);
            return result;
        }

        result.put("answer", "我可以回答：空余车位、推荐停车区域、高峰预测、异常预警、动态计费规则和今日收入。所有车牌和停车预警均来自数据库真实停车记录；你可以问：现在推荐停哪里？未来3小时会不会拥堵？");
        result.put("data", summary());
        return result;
    }

    private List<Map<String, Object>> areaStats(List<Area> areas) {
        List<Map<String, Object>> result = new ArrayList<>();
        Map<String, Double> scoreMap = scoreAreas(areas);
        for (Area area : areas) {
            if (!isBusinessAreaName(area.getParkArea())) {
                continue;
            }
            int used = safeInteger(area.getUsed());
            int unused = safeInteger(area.getUnused());
            int total = used + unused;
            double occupancyRate = rate(used, total);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("parkArea", area.getParkArea());
            item.put("used", used);
            item.put("unused", unused);
            item.put("total", total);
            item.put("price", safeDouble(area.getPrice()));
            item.put("occupancyRate", occupancyRate);
            item.put("pressureLevel", pressureLevel(occupancyRate));
            item.put("recommendScore", scoreMap.containsKey(area.getParkArea()) ? round2(scoreMap.get(area.getParkArea()) * 100) : 0);
            item.put("recommendReason", unused > 0 ? "空余" + unused + "个，当前压力" + pressureLevel(occupancyRate) : "区域已满，不建议继续引导车辆进入");
            result.add(item);
        }
        Collections.sort(result, new Comparator<Map<String, Object>>() {
            @Override
            public int compare(Map<String, Object> o1, Map<String, Object> o2) {
                return Double.compare(((Number) o2.get("recommendScore")).doubleValue(), ((Number) o1.get("recommendScore")).doubleValue());
            }
        });
        return result;
    }

    private Map<String, Object> buildRecommendation(List<Area> areas, String plateNumber) {
        List<Map<String, Object>> scoredAreas = new ArrayList<>();
        Map<String, Double> scoreMap = scoreAreas(areas);
        for (Area area : areas) {
            if (!isBusinessAreaName(area.getParkArea())) {
                continue;
            }
            int used = safeInteger(area.getUsed());
            int unused = safeInteger(area.getUnused());
            int total = used + unused;
            if (unused <= 0 || total <= 0) {
                continue;
            }
            double occupancyRate = rate(used, total);
            double score = scoreMap.containsKey(area.getParkArea()) ? scoreMap.get(area.getParkArea()) : 0;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("parkArea", area.getParkArea());
            item.put("plateNumber", plateNumber);
            item.put("unused", unused);
            item.put("total", total);
            item.put("used", used);
            item.put("price", safeDouble(area.getPrice()));
            item.put("occupancyRate", occupancyRate);
            item.put("score", round2(score * 100));
            item.put("scoreValue", score);
            item.put("waitRisk", waitRisk(occupancyRate));
            item.put("pressureLevel", pressureLevel(occupancyRate));
            item.put("estimatedFee", estimateFee(area, 2.0));
            item.put("reason", "空余" + unused + "个，占用率" + occupancyRate + "%，单价" + safeDouble(area.getPrice()) + "元/小时，综合评分最高。");
            scoredAreas.add(item);
        }
        Collections.sort(scoredAreas, new Comparator<Map<String, Object>>() {
            @Override
            public int compare(Map<String, Object> o1, Map<String, Object> o2) {
                return Double.compare(((Number) o2.get("scoreValue")).doubleValue(), ((Number) o1.get("scoreValue")).doubleValue());
            }
        });
        if (scoredAreas.isEmpty()) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("available", false);
            empty.put("parkArea", "暂无可用区域");
            empty.put("reason", "所有区域均已满位或未配置车位。请等待出场车辆释放车位。");
            empty.put("alternatives", new ArrayList<Map<String, Object>>());
            return empty;
        }
        List<Map<String, Object>> alternatives = new ArrayList<>();
        int alternativeSize = Math.min(3, scoredAreas.size());
        for (int i = 0; i < alternativeSize; i++) {
            Map<String, Object> option = new LinkedHashMap<>(scoredAreas.get(i));
            option.remove("scoreValue");
            alternatives.add(option);
        }

        Map<String, Object> best = new LinkedHashMap<>(alternatives.get(0));
        best.put("available", true);
        best.put("alternatives", alternatives);
        return best;
    }

    private Map<String, Double> scoreAreas(List<Area> areas) {
        Map<String, Double> result = new HashMap<>();
        double minPrice = Double.MAX_VALUE;
        double maxPrice = 0;
        for (Area area : areas) {
            if (safeInteger(area.getUnused()) > 0) {
                double price = safeDouble(area.getPrice());
                minPrice = Math.min(minPrice, price);
                maxPrice = Math.max(maxPrice, price);
            }
        }
        if (minPrice == Double.MAX_VALUE) {
            minPrice = 0;
        }
        Map<String, Integer> recentTurnover = recentTurnover();
        for (Area area : areas) {
            if (!isBusinessAreaName(area.getParkArea())) {
                continue;
            }
            int used = safeInteger(area.getUsed());
            int unused = safeInteger(area.getUnused());
            int total = used + unused;
            if (unused <= 0 || total <= 0) {
                result.put(area.getParkArea(), 0.0);
                continue;
            }
            double unusedRatio = unused * 1.0 / total;
            double priceScore = maxPrice == minPrice ? 1.0 : (maxPrice - safeDouble(area.getPrice())) / (maxPrice - minPrice);
            double lowPeakRisk = 1 - used * 1.0 / total;
            double turnoverScore = Math.min(1.0, recentTurnover.getOrDefault(area.getParkArea(), 0) / 8.0);
            double score = unusedRatio * 0.45 + priceScore * 0.25 + lowPeakRisk * 0.20 + turnoverScore * 0.10;
            result.put(area.getParkArea(), score);
        }
        return result;
    }

    private Map<String, Integer> recentTurnover() {
        Map<String, Integer> result = new HashMap<>();
        List<PlateRecords> records = plateRecordsMapper.selectList(Wrappers.<PlateRecords>lambdaQuery()
                .isNotNull(PlateRecords::getEndTime)
                .ge(PlateRecords::getEndTime, LocalDateTime.now().minusHours(24)));
        for (PlateRecords record : records) {
            if (StrUtil.isNotBlank(record.getParkArea())) {
                result.put(record.getParkArea(), result.getOrDefault(record.getParkArea(), 0) + 1);
            }
        }
        return result;
    }

    private List<Area> getAreas(String parkArea) {
        LambdaQueryWrapper<Area> wrapper = Wrappers.<Area>lambdaQuery();
        if (StrUtil.isNotBlank(parkArea)) {
            wrapper.eq(Area::getParkArea, parkArea.trim());
        }
        return areaMapper.selectList(wrapper);
    }

    private Area findArea(String parkArea) {
        if (StrUtil.isBlank(parkArea)) {
            Map<String, Object> recommendation = recommend("");
            Object recommendedArea = recommendation.get("parkArea");
            if (recommendedArea == null) {
                return null;
            }
            parkArea = recommendedArea.toString();
        }
        return areaMapper.selectOne(Wrappers.<Area>lambdaQuery().eq(Area::getParkArea, parkArea));
    }

    private Area matchArea(String question) {
        List<Area> areas = areaMapper.selectList(Wrappers.<Area>lambdaQuery());
        for (Area area : areas) {
            if (StrUtil.isNotBlank(area.getParkArea()) && question.contains(area.getParkArea())) {
                return area;
            }
        }
        return null;
    }

    private Map<String, Object> buildBilling(Area area, double durationHours, LocalDateTime time) {
        double freeMinutes = 15;
        double billedHours = Math.max(0, durationHours - freeMinutes / 60.0);
        double basePrice = safeDouble(area.getPrice());
        double peakFactor = isPeakHour(time.getHour()) ? 1.2 : 1.0;
        double occupancyRate = rate(safeInteger(area.getUsed()), safeInteger(area.getUsed()) + safeInteger(area.getUnused()));
        double occupancyFactor = occupancyRate >= 85 ? 1.15 : (occupancyRate <= 40 ? 0.9 : 1.0);
        double dailyCap = 120.0;
        double rawFee = billedHours * basePrice * peakFactor * occupancyFactor;
        double finalFee = round2(Math.min(rawFee, dailyCap));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("available", true);
        data.put("parkArea", area.getParkArea());
        data.put("durationHours", round2(durationHours));
        data.put("freeMinutes", freeMinutes);
        data.put("billedHours", round2(billedHours));
        data.put("basePrice", basePrice);
        data.put("peakFactor", peakFactor);
        data.put("occupancyRate", occupancyRate);
        data.put("occupancyFactor", occupancyFactor);
        data.put("dailyCap", dailyCap);
        data.put("finalFee", finalFee);
        data.put("explain", "前15分钟免费，基础费用=计费小时×区域单价；早晚高峰×1.2，高占用×1.15，低占用×0.9，单日封顶120元。");
        return data;
    }

    private double estimateFee(Area area, double durationHours) {
        return ((Number) buildBilling(area, durationHours, LocalDateTime.now()).get("finalFee")).doubleValue();
    }

    private List<String> explanations(Map<String, Object> summary, List<Map<String, Object>> areaStats,
                                      List<Map<String, Object>> forecast, List<Map<String, Object>> alerts,
                                      Map<String, Object> recommendation) {
        List<String> result = new ArrayList<>();
        result.add("当前全场占用率" + summary.get("occupancyRate") + "%：总车位" + summary.get("totalSpaces") + "个，空余" + summary.get("unusedSpaces") + "个。");
        if (!areaStats.isEmpty()) {
            Map<String, Object> top = areaStats.get(0);
            result.add("推荐优先引导到" + top.get("parkArea") + "，推荐分" + top.get("recommendScore") + "，原因：" + top.get("recommendReason") + "。");
        }
        if (recommendation.get("reason") != null) {
            result.add("智能推荐说明：" + recommendation.get("reason"));
        }
        if (!forecast.isEmpty()) {
            result.add("未来" + forecast.size() + "小时预测最高压力为" + highestPressure(forecast) + "，用于提前安排入口引导和人工巡检。");
        }
        if (!alerts.isEmpty() && !"NORMAL".equals(alerts.get(0).get("type"))) {
            result.add("当前存在" + alerts.size() + "条预警，首要处理：" + alerts.get(0).get("message"));
        } else {
            result.add("当前无异常停车或高占用预警，系统运行平稳。");
        }
        return result;
    }

    private String highestPressure(List<Map<String, Object>> forecast) {
        String level = "低";
        double maxRate = -1;
        for (Map<String, Object> item : forecast) {
            double occupancyRate = ((Number) item.get("occupancyRate")).doubleValue();
            if (occupancyRate > maxRate) {
                maxRate = occupancyRate;
                level = item.get("pressureLevel") + "(" + occupancyRate + "%)";
            }
        }
        return level;
    }

    private int averageCountByHour(List<PlateRecords> records, int hour, boolean entry) {
        int count = 0;
        for (PlateRecords record : records) {
            LocalDateTime time = entry ? record.getStartTime() : record.getEndTime();
            if (time != null && time.getHour() == hour) {
                count++;
            }
        }
        return (int) Math.round(count / 14.0);
    }


    private Map<String, Object> alert(int id, String type, String level, String plateNumber, String parkArea, String message) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", id);
        item.put("type", type);
        item.put("level", level);
        item.put("plateNumber", plateNumber);
        item.put("parkArea", parkArea);
        item.put("message", message);
        item.put("status", 0);
        item.put("createdTime", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        return item;
    }

    private boolean isBusinessAreaName(String parkArea) {
        return StrUtil.isNotBlank(parkArea)
                && !parkArea.matches("^[A-Za-z0-9_\\-]+$")
                && !parkArea.matches("^\\d+$");
    }

    private boolean isValidPlateNumber(String plateNumber) {
        return StrUtil.isNotBlank(plateNumber)
                && plateNumber.matches("^[京津沪渝冀豫云辽黑湘皖鲁新苏浙赣鄂桂甘晋蒙陕吉闽贵粤青藏川宁琼][A-Z][A-Z0-9]{5,6}$");
    }
    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private boolean isPeakHour(int hour) {
        return (hour >= 7 && hour <= 9) || (hour >= 17 && hour <= 20);
    }

    private String pressureLevel(double occupancyRate) {
        if (occupancyRate >= 90) {
            return "严重";
        }
        if (occupancyRate >= 75) {
            return "高";
        }
        if (occupancyRate >= 50) {
            return "中";
        }
        return "低";
    }

    private String waitRisk(double occupancyRate) {
        if (occupancyRate >= 85) {
            return "高";
        }
        if (occupancyRate >= 60) {
            return "中";
        }
        return "低";
    }

    private double rate(int value, int total) {
        if (total <= 0) {
            return 0;
        }
        return round2(value * 100.0 / total);
    }

    private int safeInteger(Integer value) {
        return value == null ? 0 : value;
    }

    private double safeDouble(Double value) {
        return value == null ? 0 : value;
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }
}