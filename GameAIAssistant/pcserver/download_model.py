# download_model.py - 模型自动下载工具
import argparse
import subprocess
import sys
import os

RECOMMENDED_MODELS = {
    "qwen-vl-chat": {
        "name": "qwen2.5:7b",
        "description": "通义千问视觉模型，综合能力强，推荐配置",
        "vram": "8GB+",
        "backend": "ollama",
        "tags": ["视觉", "中文", "推荐"]
    },
    "minicpm-v": {
        "name": "minicpm-v:8b",
        "description": "MiniCPM-V 多模态模型，高精度端侧视觉分析",
        "vram": "10GB+",
        "backend": "ollama",
        "tags": ["视觉", "端侧优化"]
    },
    "llava": {
        "name": "llava:7b",
        "description": "Llava通用视觉模型，轻量级",
        "vram": "8GB+",
        "backend": "ollama",
        "tags": ["视觉", "轻量"]
    },
    "llama-vision": {
        "name": "llama3.2-vision:11b",
        "description": "Llama 3.2 视觉模型，Meta官方多模态",
        "vram": "12GB+",
        "backend": "ollama",
        "tags": ["视觉", "多模态"]
    },
    "qwen-pure": {
        "name": "qwen2.5:3b",
        "description": "通义千问纯文本模型，无视觉，轻量极速",
        "vram": "4GB+",
        "backend": "ollama",
        "tags": ["纯文本", "轻量", "极速"]
    },
    "deepseek": {
        "name": "deepseek-r1:7b",
        "description": "DeepSeek推理模型，逻辑分析强",
        "vram": "8GB+",
        "backend": "ollama",
        "tags": ["推理", "文本"]
    }
}

MODEL_TIERS = {
    "推荐": ["qwen-vl-chat", "minicpm-v"],
    "轻量": ["llava", "qwen-pure"],
    "进阶": ["llama-vision", "deepseek"],
}


def download_ollama_model(model_key: str):
    """通过Ollama下载模型"""
    # 检查ollama是否安装（ollama 不支持 --version，用 list 检测）
    try:
        result = subprocess.run(["ollama", "list"], capture_output=True, text=True, timeout=10)
        if result.returncode != 0:
            raise FileNotFoundError("ollama 命令不可用")
    except (subprocess.TimeoutExpired, FileNotFoundError):
        print("[错误] 未检测到Ollama")
        print("请先安装: https://ollama.com/download")
        print("安装后请确保 ollama 命令在系统 PATH 中")
        return False

    model = RECOMMENDED_MODELS.get(model_key)
    if not model:
        # 尝试作为模型名直接下载
        model_name = model_key
        desc = "自定义模型"
    else:
        model_name = model["name"]
        desc = model["description"]

    print(f"[下载] {model_name}")
    print(f"  描述: {desc}")
    print(f"  预计时间: 5-15分钟 (取决于网速)")
    print()

    try:
        result = subprocess.run(
            ["ollama", "pull", model_name],
            check=False,
            text=True
        )
        if result.returncode == 0:
            print(f"\n[成功] {model_name} 下载完成！")
            return True
        else:
            print(f"\n[失败] 下载出错: {result.stderr}")
            return False
    except Exception as e:
        print(f"\n[错误] {e}")
        return False


def list_available():
    """列出可下载的推荐模型"""
    print("\n" + "=" * 72)
    print("  📦 GameAI 推荐模型列表")
    print("=" * 72)

    for tier_name, tier_keys in MODEL_TIERS.items():
        print(f"\n  ▎{tier_name}级别:")
        print(f"  {'标识':<20} {'模型名':<25} {'显存':<10} {'说明'}")
        print(f"  {'-'*18} {'-'*25} {'-'*10} {'-'*20}")
        for key in tier_keys:
            if key in RECOMMENDED_MODELS:
                m = RECOMMENDED_MODELS[key]
                print(f"  {key:<18} {m['name']:<25} {m['vram']:<10} {m['description']}")

    print(f"\n  用法: python download_model.py --model <标识>")
    print(f"  示例: python download_model.py --model qwen-vl-chat")
    print(f"  列出已安装: python download_model.py --installed")
    print()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description='GameAI 模型下载工具')
    parser.add_argument('--model', type=str, help='模型标识（qwen-vl-chat/minicpm-v/llava/llama-vision/qwen-pure/deepseek）')
    parser.add_argument('--list', action='store_true', help='列出可用模型')
    parser.add_argument('--installed', action='store_true', help='列出已安装的Ollama模型')

    args = parser.parse_args()

    if args.installed:
        try:
            result = subprocess.run(["ollama", "list"], capture_output=True, text=True, timeout=10)
            if result.returncode == 0:
                print("\n已安装的Ollama模型:\n")
                print(result.stdout)
            else:
                print("[错误] 无法获取已安装模型列表")
        except (FileNotFoundError, subprocess.TimeoutExpired):
            print("[错误] 未检测到Ollama，请先安装: https://ollama.com/download")
    elif args.list or not args.model:
        list_available()
    else:
        success = download_ollama_model(args.model)
        if not success:
            print("\n提示: 你可以手动下载模型:")
            print("  ollama pull qwen2.5:7b")
            print("\n或查看所有可用模型:")
            print("  python download_model.py --list")

    print()
    print("下载完成后，重新启动服务即可使用AI分析功能:")
    print("  python main.py --host 0.0.0.0 --port 8765")
