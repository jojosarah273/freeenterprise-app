"""Golbez spellset configuration."""


def apply(env):
    initial_black = [
        '#Fire1', '#Fire2', '#Fire3',
        '#Ice1', '#Ice2', '#Ice3',
        '#Lit1', '#Lit2', '#Lit3',
        '#Virus'
    ]
    learned_black = [
        (32, '#Psych'),
        (35, '#Drain'),
        (42, '#Quake'),
        (55, '#Nuke'),
        (60, '#Meteo'),
    ]
    learned_text = '\n        '.join(f'{level} {spell}' for level, spell in learned_black)
    env.add_scripts(
        'spellset(#FusoyaWhite) { initial { } }',
        'spellset(#FusoyaBlack) { initial { ' + ' '.join(initial_black) + ' }\n'
        '    learned {\n        ' + learned_text + '\n    }\n}'
    )
