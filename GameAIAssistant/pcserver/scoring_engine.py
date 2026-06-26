# scoring_engine.py - 本机评分引擎（无AI依赖，纯规则计算）
import time
import logging
from typing import Dict, Any, List

logger = logging.getLogger('GameAI.Scoring')


class LocalScoringEngine:
    """基于规则的对局评分引擎，不依赖AI模型即可运行"""

    # 评分维度配置
    CATEGORIES = {
        "kda": {"name": "KDA", "weight": 25},
        "economy": {"name": "经济", "weight": 20},
        "teamfight": {"name": "参团率", "weight": 15},
        "vision": {"name": "视野", "weight": 15},
        "damage": {"name": "输出", "weight": 10},
        "survival": {"name": "生存", "weight": 5},
        "develop": {"name": "发育", "weight": 5},
        "tempo": {"name": "节奏", "weight": 5}
    }

    def __init__(self):
        self._event_history: Dict[str, List[Dict]] = {}
        self._match_data: Dict[str, Dict] = {}

    def record_event(self, device_id: str, event: str, data: Dict):
        """记录游戏事件"""
        if device_id not in self._event_history:
            self._event_history[device_id] = []
        self._event_history[device_id].append({
            "event": event,
            "data": data,
            "time": time.time()
        })

    def calculate_from_state(self, state: Dict) -> Dict[str, Any]:
        """根据当前对局状态计算评分"""
        kills = state.get("kills", 0)
        deaths = state.get("deaths", 0)
        assists = state.get("assists", 0)

        # KDA计算
        kda = (kills + assists) / max(deaths, 1)
        kda_score = min(25, int(kda * 3))
        if kda >= 8: kda_score = 23
        if kda >= 15: kda_score = 25

        # 经济
        gold_per_min = state.get("gpm", state.get("gold_per_min", 300))
        economy_score = min(20, gold_per_min // 40)

        # 参团率
        participation = state.get("participation_rate", state.get("teamfight_participation", 0.3))
        teamfight_score = min(15, int(participation * 20))

        # 视野
        vision = state.get("vision_score", state.get("vision", 10))
        vision_score = min(15, vision // 5)

        # 输出
        damage = state.get("damage_dealt", state.get("damage", 5000))
        damage_score = min(10, damage // 3000)

        # 生存
        survival_score = max(0, 5 - deaths)
        if deaths == 0: survival_score = 5

        # 发育
        creep_score = state.get("creep_score", state.get("cs", 40))
        develop_score = min(5, creep_score // 50)

        # 节奏（目标物）
        objectives = state.get("objectives", state.get("towers", 0))
        tempo_score = min(5, objectives)

        # 总分
        total = sum([kda_score, economy_score, teamfight_score, vision_score,
                     damage_score, survival_score, develop_score, tempo_score])

        # 评级
        rating = self._compute_grade(total)

        # 建议
        suggestions = []
        if deaths >= 5: suggestions.append("减少阵亡次数，注意走位")
        if kda < 2: suggestions.append("多参与团战，提高击杀参与")
        if gold_per_min < 300: suggestions.append("加强补刀，提高经济获取")
        if participation < 0.3: suggestions.append("关注团战时机，及时支援")

        # 语音提示
        voice_text = None
        if total >= 90:
            voice_text = f"顶级表现！当前评分{total}"
        elif total <= 30:
            voice_text = f"评分偏低{total}分，注意调整"

        return {
            "score": total,
            "rating": rating,
            "suggestions": suggestions,
            "voice_text": voice_text,
            "detail": f"KDA:{kills}/{deaths}/{assists} | 评分:{total}",
            "categories": {
                "kda": {"name": "KDA", "score": kda_score, "maxScore": 25, "rating": self._grade_for_category(kda_score, 25), "detail": ""},
                "economy": {"name": "经济", "score": economy_score, "maxScore": 20, "rating": self._grade_for_category(economy_score, 20), "detail": ""},
                "teamfight": {"name": "参团率", "score": teamfight_score, "maxScore": 15, "rating": self._grade_for_category(teamfight_score, 15), "detail": ""},
                "vision": {"name": "视野", "score": vision_score, "maxScore": 15, "rating": self._grade_for_category(vision_score, 15), "detail": ""},
                "damage": {"name": "输出", "score": damage_score, "maxScore": 10, "rating": self._grade_for_category(damage_score, 10), "detail": ""},
                "survival": {"name": "生存", "score": survival_score, "maxScore": 5, "rating": self._grade_for_category(survival_score, 5), "detail": ""},
                "develop": {"name": "发育", "score": develop_score, "maxScore": 5, "rating": self._grade_for_category(develop_score, 5), "detail": ""},
                "tempo": {"name": "节奏", "score": tempo_score, "maxScore": 5, "rating": self._grade_for_category(tempo_score, 5), "detail": ""}
            }
        }

    def get_final_score(self, device_id: str) -> Dict[str, Any]:
        """获取对局最终评分"""
        events = self._event_history.get(device_id, [])
        state = self._match_data.get(device_id, {})
        return self.calculate_from_state(state)

    def reset(self, device_id: str):
        """重置设备数据"""
        if device_id in self._event_history:
            del self._event_history[device_id]
        if device_id in self._match_data:
            del self._match_data[device_id]

    @staticmethod
    def _compute_grade(score: int) -> str:
        if score >= 90: return "S"
        elif score >= 80: return "A"
        elif score >= 65: return "B"
        elif score >= 50: return "C"
        else: return "D"

    @staticmethod
    def _grade_for_category(score: int, max_score: int) -> str:
        ratio = score / max(max_score, 1)
        if ratio >= 0.9: return "S"
        elif ratio >= 0.8: return "A"
        elif ratio >= 0.65: return "B"
        elif ratio >= 0.5: return "C"
        else: return "D"
