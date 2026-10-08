#!/usr/bin/env python3
"""Telegram-бот «Метро Москвы».

Строит маршрут по сообщению вида «Митино — Выхино» и открывает Mini App со схемой.
Нужен только Python 3.8+, сторонних библиотек нет.

Настройка — переменные окружения или файл bot/config.ini (см. config.example.ini):
  BOT_TOKEN   токен от @BotFather
  WEBAPP_URL  адрес Mini App, например https://dagstatus.github.io/Moscw_Metro/

Данные схемы бот берёт с WEBAPP_URL/metro.json (раз в 6 часов проверяет обновления),
а если сайт недоступен — из docs/metro.json рядом с ботом. Поэтому после выпуска новой
версии через update.bat и публикации на GitHub бот подхватит схему сам.
"""

import configparser
import heapq
import html
import json
import os
import re
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
LOCAL_DATA = os.path.join(HERE, '..', 'docs', 'metro.json')
REFRESH_SECONDS = 6 * 3600


def load_config():
    cfg = {'BOT_TOKEN': os.environ.get('BOT_TOKEN', ''), 'WEBAPP_URL': os.environ.get('WEBAPP_URL', '')}
    path = os.path.join(HERE, 'config.ini')
    if os.path.exists(path):
        cp = configparser.ConfigParser()
        cp.read(path, encoding='utf-8')
        for k in cfg:
            if not cfg[k]:
                cfg[k] = cp.get('bot', k.lower(), fallback='').strip()
    if cfg['WEBAPP_URL'] and not cfg['WEBAPP_URL'].endswith('/'):
        cfg['WEBAPP_URL'] += '/'
    return cfg


# ---------------------------------------------------------------- данные и маршрут

def normalize(s):
    s = s.lower().replace('ё', 'е').replace('-', ' ').replace('.', ' ')
    return re.sub(r'\s+', ' ', s).strip()


class Metro:
    def __init__(self, data):
        self.updated = data.get('updated', '')
        self.version = data.get('version', '')
        self.lines = data['lines']
        self.line_idx = {l['id']: i for i, l in enumerate(self.lines)}
        self.stations = data['stations']
        for i, s in enumerate(self.stations):
            s['idx'] = i
            s['line'] = self.line_idx[s['l']]
        parent = list(range(len(self.stations)))

        def find(x):
            while parent[x] != x:
                parent[x] = parent[parent[x]]
                x = parent[x]
            return x

        for a, b, _ in data['transfers']:
            ra, rb = find(a), find(b)
            if ra != rb:
                parent[ra] = rb
        groups = {}
        for s in self.stations:
            s['group'] = groups.setdefault(find(s['idx']), len(groups))
        # пункты поиска: одно название в одном пересадочном узле
        by_key = {}
        for s in self.stations:
            by_key.setdefault((s['group'], normalize(s['n'])), []).append(s['idx'])
        self.entries = []
        self.entry_of = [None] * len(self.stations)
        for (_, norm), idxs in by_key.items():
            e = {'name': self.stations[idxs[0]]['n'], 'norm': norm, 'stations': idxs}
            for i in idxs:
                self.entry_of[i] = e
            self.entries.append(e)
        self.adj = [[] for _ in self.stations]
        for a, b, m in data['edges']:
            self.adj[a].append((b, m, False))
            self.adj[b].append((a, m, False))
        for a, b, m in data['transfers']:
            self.adj[a].append((b, m + self.lines[self.stations[b]['line']]['wait'], True))
            self.adj[b].append((a, m + self.lines[self.stations[a]['line']]['wait'], True))

    def search(self, query, limit=8):
        q = normalize(query)
        if not q:
            return []
        exact, a, b, c = [], [], [], []
        for e in self.entries:
            if e['norm'] == q:
                exact.append(e)
            elif e['norm'].startswith(q):
                a.append(e)
            elif (' ' + q) in e['norm']:
                b.append(e)
            elif q in e['norm']:
                c.append(e)
        return (exact + a + b + c)[:limit], len(exact)

    def line_names(self, e):
        return ', '.join(self.lines[self.stations[i]['line']]['name'] for i in e['stations'])

    def route(self, src, dst):
        n = len(self.stations)
        dist = [float('inf')] * n
        prev = [-1] * n
        prev_walk = [False] * n
        target = set(dst)
        heap = []
        for s in src:
            if s in target:
                return None
            dist[s] = 0
            heapq.heappush(heap, (0, s))
        done = [False] * n
        end = -1
        while heap:
            d, u = heapq.heappop(heap)
            if done[u]:
                continue
            done[u] = True
            if u in target:
                end = u
                break
            for v, c, w in self.adj[u]:
                nd = d + c
                if nd < dist[v] - 1e-4:
                    dist[v], prev[v], prev_walk[v] = nd, u, w
                    heapq.heappush(heap, (nd, v))
        if end < 0:
            return None
        nodes, walks = [], []
        v = end
        while v >= 0:
            nodes.insert(0, v)
            walks.insert(0, prev_walk[v])
            v = prev[v]
        steps = []
        cur = {'walk': False, 'line': self.stations[nodes[0]]['line'], 'stations': [nodes[0]], 'minutes': 0}
        for i in range(1, len(nodes)):
            u, v = nodes[i - 1], nodes[i]
            c = dist[v] - dist[u]
            if walks[i]:
                if cur['walk']:
                    cur['stations'].append(v)
                    cur['minutes'] += c
                else:
                    if len(cur['stations']) > 1:
                        steps.append(cur)
                    cur = {'walk': True, 'stations': [u, v], 'minutes': c}
            else:
                if cur['walk']:
                    steps.append(cur)
                    cur = {'walk': False, 'line': self.stations[u]['line'], 'stations': [u], 'minutes': 0}
                cur['stations'].append(v)
                cur['minutes'] += c
        if cur['walk'] or len(cur['stations']) > 1:
            steps.append(cur)
        total, transfers, stops, first = dist[end], 0, 0, True
        for s in steps:
            if s['walk']:
                transfers += 1
                last_line = self.lines[self.stations[s['stations'][-1]]['line']]
                s['minutes'] = max(1, s['minutes'] - last_line['wait'])
            else:
                stops += len(s['stations']) - 1
                if first:
                    total += self.lines[s['line']]['wait']
                    first = False
        return {'steps': steps, 'minutes': total, 'transfers': transfers, 'stops': stops, 'nodes': nodes}

    def direction(self, step):
        line = self.lines[step['line']]
        if line['ring'] or len(step['stations']) < 2:
            return None
        a, b = step['stations'][-2], step['stations'][-1]
        for p in line['paths']:
            for k in range(len(p) - 1):
                if p[k] == a and p[k + 1] == b:
                    return self.stations[p[-1]]['n']
                if p[k] == b and p[k + 1] == a:
                    return self.stations[p[0]]['n']
        return None


def plural(n, one, few, many):
    a, b = n % 10, n % 100
    if a == 1 and b != 11:
        return one
    if 2 <= a <= 4 and not 12 <= b <= 14:
        return few
    return many


def fmt_min(m):
    return f'{m} мин' if m < 60 else f'{m // 60} ч {m % 60} мин'


def short_name(line):
    if line['type'] == 'mcd':
        return line['name'].split(' ')[0]
    if line['type'] == 'mcc':
        return 'МЦК'
    return line['name'] + ' линия'


# ---------------------------------------------------------------- Telegram API

class Bot:
    def __init__(self, cfg):
        self.token = cfg['BOT_TOKEN']
        self.webapp = cfg['WEBAPP_URL']
        self.api = f'https://api.telegram.org/bot{self.token}/'
        self.metro = None
        self.data_source = ''
        self.pending = {}  # chat_id -> индекс станции «откуда», пока ждём «куда»
        self.lock = threading.Lock()

    def call(self, method, **params):
        body = json.dumps({k: v for k, v in params.items() if v is not None}).encode('utf-8')
        req = urllib.request.Request(self.api + method, data=body, headers={'Content-Type': 'application/json'})
        try:
            with urllib.request.urlopen(req, timeout=70) as r:
                return json.loads(r.read().decode('utf-8')).get('result')
        except urllib.error.HTTPError as e:
            print(f'{method}: {e.code} {e.read().decode("utf-8", "replace")}', file=sys.stderr)
        except Exception as e:  # сеть
            print(f'{method}: {e}', file=sys.stderr)
        return None

    # ---------- данные

    def refresh_data(self):
        data, source = None, ''
        if self.webapp:
            try:
                with urllib.request.urlopen(self.webapp + 'metro.json', timeout=30) as r:
                    data, source = json.loads(r.read().decode('utf-8')), self.webapp + 'metro.json'
            except Exception as e:
                print(f'Не удалось скачать схему с сайта ({e}), беру локальный файл', file=sys.stderr)
        if data is None:
            with open(LOCAL_DATA, encoding='utf-8') as f:
                data, source = json.load(f), LOCAL_DATA
        metro = Metro(data)
        with self.lock:
            changed = self.metro is None or self.metro.version != metro.version
            self.metro = metro
        if changed:
            print(f'Схема {metro.version} (на {metro.updated}): {len(metro.stations)} станций, источник {source}')

    def refresher(self):
        while True:
            time.sleep(REFRESH_SECONDS)
            try:
                self.refresh_data()
            except Exception as e:
                print(f'Обновление схемы: {e}', file=sys.stderr)

    # ---------- кнопки

    def map_button(self, chat_type, frm=None, to=None, text='🗺 Открыть схему'):
        if not self.webapp:
            return None
        url = self.webapp
        if frm is not None and to is not None:
            url += '?' + urllib.parse.urlencode({'from': frm, 'to': to})
        if chat_type == 'private':
            return {'text': text, 'web_app': {'url': url}}
        return {'text': text, 'url': url}

    # ---------- ответы

    def help_text(self):
        m = self.metro
        d = m.updated.split('-')
        upd = f'{d[2]}.{d[1]}.{d[0]}' if len(d) == 3 else m.updated
        return ('🚇 <b>Метро Москвы</b>\n\n'
                'Напишите, откуда и куда ехать, например:\n'
                '<code>Митино — Выхино</code>\n<code>Комсомольская, Деловой центр</code>\n\n'
                'Можно отправить одну станцию, а потом вторую. '
                'Схема с поиском маршрута открывается кнопкой ниже и работает без интернета.\n\n'
                f'Схема актуальна на {upd}.')

    def route_text(self, m, src, dst, r):
        a = html.escape(src['name'])
        b = html.escape(dst['name'])
        tr = f"{r['transfers']} {plural(r['transfers'], 'пересадка', 'пересадки', 'пересадок')}" if r['transfers'] else 'без пересадок'
        out = [f'<b>{a} → {b}</b>', f"≈ <b>{fmt_min(round(r['minutes']))}</b> · {tr} · {r['stops']} {plural(r['stops'], 'перегон', 'перегона', 'перегонов')}", '']
        for st in r['steps']:
            s1, s2 = m.stations[st['stations'][0]], m.stations[st['stations'][-1]]
            if st['walk']:
                line = m.lines[s2['line']]
                out.append(f"🚶 Переход на «{html.escape(s2['n'])}», {html.escape(short_name(line))} · {fmt_min(round(st['minutes']))}")
                continue
            line = m.lines[st['line']]
            n = len(st['stations']) - 1
            info = f"{n} {plural(n, 'перегон', 'перегона', 'перегонов')} · {fmt_min(round(st['minutes']))}"
            dr = m.direction(st)
            if dr and dr != s2['n']:
                info += f' · в сторону «{html.escape(dr)}»'
            out.append(f"{line.get('emoji') or '•'} <b>{html.escape(short_name(line))}</b>\n"
                       f"{html.escape(s1['n'])} → {html.escape(s2['n'])}\n<i>{info}</i>")
        return '\n'.join(out)

    def best_route(self, m, froms, tos):
        best = None
        for f in froms:
            for t in tos:
                if f is t:
                    continue
                r = m.route(f['stations'], t['stations'])
                if r and (best is None or r['minutes'] < best[2]['minutes']):
                    best = (f, t, r)
        return best

    def resolve(self, m, text):
        """Список подходящих пунктов: все точные совпадения или до 8 вариантов."""
        found, exact = m.search(text, 8)
        if exact:
            return found[:exact], True
        # «до Театральной», «от Сокольников»: отбрасываем падежное окончание каждого слова
        words = normalize(text).split()
        for cut in (1, 2, 3):
            if found or not words or min(len(w) for w in words) <= cut + 3:
                break
            stem = ' '.join(w[:-cut] if len(w) > cut + 3 else w for w in words)
            found = [e for e in m.entries if all(any(x.startswith(sw) for x in e['norm'].split()) for sw in stem.split())][:8]
        return found, len(found) == 1

    def send_route(self, chat, chat_type, froms, tos, reply_to=None):
        m = self.metro
        best = self.best_route(m, froms, tos)
        if not best:
            return self.call('sendMessage', chat_id=chat, text='Это одна и та же станция или маршрут не найден.')
        f, t, r = best
        rows = []
        btn = self.map_button(chat_type, f['stations'][0], t['stations'][0], '🗺 Показать на схеме')
        if btn:
            rows.append([btn])
        rows.append([{'text': '🔄 Обратно', 'callback_data': f"r:{t['stations'][0]}:{f['stations'][0]}"}])
        self.call('sendMessage', chat_id=chat, text=self.route_text(m, f, t, r), parse_mode='HTML',
                  reply_markup={'inline_keyboard': rows}, reply_to_message_id=reply_to,
                  link_preview_options={'is_disabled': True})

    def ask_choice(self, chat, m, options, role, text):
        rows = [[{'text': f"{o['name']} ({m.line_names(o)})"[:60], 'callback_data': f"{role}:{o['stations'][0]}"}] for o in options]
        self.call('sendMessage', chat_id=chat, text=text, reply_markup={'inline_keyboard': rows})

    def on_message(self, msg):
        chat = msg['chat']['id']
        chat_type = msg['chat'].get('type', 'private')
        text = (msg.get('text') or '').strip()
        if not text:
            return
        m = self.metro
        if text.startswith('/'):
            cmd = text.split()[0].split('@')[0].lower()
            if cmd in ('/start', '/help', '/map'):
                btn = self.map_button(chat_type)
                self.call('sendMessage', chat_id=chat, text=self.help_text(), parse_mode='HTML',
                          reply_markup={'inline_keyboard': [[btn]]} if btn else None)
            return
        parts = [p for p in re.split(r'\s*(?:→|->|—|–|>|\n|;|,|\s-\s|\sдо\s|\sна\s|\sв\s)\s*', re.sub(r'^(от|из|с)\s+', '', text, flags=re.I)) if p.strip()]
        if len(parts) >= 2:
            (fr, fok), (to, tok) = self.resolve(m, parts[0]), self.resolve(m, parts[-1])
            if not fr or not to:
                bad = parts[0] if not fr else parts[-1]
                self.call('sendMessage', chat_id=chat, text=f'Не нашёл станцию «{bad}». Проверьте название.')
                return
            if not fok:
                self.pending[chat] = ('to', to if tok else None)
                self.ask_choice(chat, m, fr, 'f', 'Уточните, откуда:')
                return
            if not tok:
                self.pending[chat] = ('from', fr)
                self.ask_choice(chat, m, to, 't', 'Уточните, куда:')
                return
            self.send_route(chat, chat_type, fr, to)
            return
        found, ok = self.resolve(m, parts[0] if parts else text)
        if not found:
            self.call('sendMessage', chat_id=chat, text='Не нашёл такую станцию. Напишите, например: Митино — Выхино')
            return
        if not ok:
            self.ask_choice(chat, m, found, 's', 'Какую станцию вы имели в виду?')
            return
        self.on_station(chat, chat_type, found)

    def on_station(self, chat, chat_type, entries):
        """Пришла одна станция: либо это «откуда», либо вторая половина маршрута."""
        p = self.pending.pop(chat, None)
        if p and p[0] == 'from' and p[1]:
            return self.send_route(chat, chat_type, p[1], entries)
        if p and p[0] == 'to' and p[1]:
            return self.send_route(chat, chat_type, entries, p[1])
        self.pending[chat] = ('from', entries)
        self.call('sendMessage', chat_id=chat, text=f"Откуда: {entries[0]['name']}. Теперь напишите, куда ехать.")

    def on_callback(self, cq):
        self.call('answerCallbackQuery', callback_query_id=cq['id'])
        msg = cq.get('message') or {}
        chat = msg.get('chat', {}).get('id')
        chat_type = msg.get('chat', {}).get('type', 'private')
        if chat is None:
            return
        m = self.metro
        data = cq.get('data', '')
        try:
            kind, *nums = data.split(':')
            nums = [int(x) for x in nums]
        except ValueError:
            return
        if any(x < 0 or x >= len(m.stations) for x in nums):
            return
        if kind == 'r' and len(nums) == 2:
            self.send_route(chat, chat_type, [m.entry_of[nums[0]]], [m.entry_of[nums[1]]])
        elif kind == 'f' and nums:
            p = self.pending.pop(chat, None)
            e = [m.entry_of[nums[0]]]
            if p and p[0] == 'to' and p[1]:
                self.send_route(chat, chat_type, e, p[1])
            else:
                self.pending[chat] = ('from', e)
                self.call('sendMessage', chat_id=chat, text=f"Откуда: {e[0]['name']}. Теперь напишите, куда ехать.")
        elif kind == 't' and nums:
            p = self.pending.pop(chat, None)
            if p and p[0] == 'from' and p[1]:
                self.send_route(chat, chat_type, p[1], [m.entry_of[nums[0]]])
        elif kind == 's' and nums:
            self.on_station(chat, chat_type, [m.entry_of[nums[0]]])

    # ---------- запуск

    def run(self):
        self.refresh_data()
        me = self.call('getMe')
        if not me:
            sys.exit('Не удалось подключиться к Telegram: проверьте BOT_TOKEN и интернет.')
        print(f"Бот @{me['username']} запущен. Остановить: Ctrl+C")
        self.call('setMyCommands', commands=[{'command': 'start', 'description': 'Как пользоваться'},
                                             {'command': 'map', 'description': 'Открыть схему метро'}])
        if self.webapp:
            self.call('setChatMenuButton', menu_button={'type': 'web_app', 'text': 'Схема', 'web_app': {'url': self.webapp}})
        threading.Thread(target=self.refresher, daemon=True).start()
        offset = 0
        while True:
            updates = self.call('getUpdates', offset=offset, timeout=50, allowed_updates=['message', 'callback_query'])
            if updates is None:
                time.sleep(3)
                continue
            for u in updates:
                offset = u['update_id'] + 1
                try:
                    if 'message' in u:
                        self.on_message(u['message'])
                    elif 'callback_query' in u:
                        self.on_callback(u['callback_query'])
                except Exception as e:
                    print(f'Ошибка обработки: {e!r}', file=sys.stderr)


if __name__ == '__main__':
    cfg = load_config()
    if not cfg['BOT_TOKEN']:
        sys.exit('Не задан BOT_TOKEN. Скопируйте bot/config.example.ini в bot/config.ini и впишите токен от @BotFather.')
    try:
        Bot(cfg).run()
    except KeyboardInterrupt:
        pass
