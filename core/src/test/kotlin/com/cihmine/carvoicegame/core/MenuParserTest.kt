package com.cihmine.carvoicegame.core

import com.cihmine.carvoicegame.core.command.MenuChoice
import com.cihmine.carvoicegame.core.command.MenuParser
import kotlin.test.Test
import kotlin.test.assertEquals

/** 처음 화면 "어떤 게임을 시작할까요?"에 대한 대답 해석 */
class MenuParserTest {
    @Test
    fun `게임 이름만 말하면 난이도는 비워 둔다(화면에 고른 값 사용)`() {
        assertEquals(MenuChoice.Start(GameKind.WORD_CHAIN, null), MenuParser.parse("끝말잇기"))
        assertEquals(MenuChoice.Start(GameKind.WORD_CHAIN, null), MenuParser.parse("끝말잇기 하자"))
        assertEquals(MenuChoice.Start(GameKind.CHOSEONG, null), MenuParser.parse("초성 대결 할래"))
        assertEquals(MenuChoice.Start(GameKind.CHOSEONG, null), MenuParser.parse("초성 게임"))
    }

    @Test
    fun `게임과 난이도를 한 번에 말하면 둘 다 알아듣는다`() {
        assertEquals(MenuChoice.Start(GameKind.CHOSEONG, Difficulty.HARD), MenuParser.parse("초성 대결 어렵게"))
        assertEquals(MenuChoice.Start(GameKind.WORD_CHAIN, Difficulty.EASY), MenuParser.parse("쉬운 끝말잇기"))
        assertEquals(MenuChoice.Start(GameKind.WORD_CHAIN, Difficulty.NORMAL), MenuParser.parse("끝말잇기 보통으로"))
    }

    @Test
    fun `준비 중인 게임`() {
        assertEquals(MenuChoice.NotReady(GameKind.TWENTY_QUESTIONS), MenuParser.parse("스무고개 하자"))
        assertEquals(MenuChoice.NotReady(GameKind.GUESS_WHO), MenuParser.parse("양세찬 게임"))
    }

    @Test
    fun `아무거나·이어서·규칙`() {
        assertEquals(MenuChoice.Any, MenuParser.parse("아무거나"))
        assertEquals(MenuChoice.Any, MenuParser.parse("네가 골라"))
        assertEquals(MenuChoice.Resume, MenuParser.parse("이어서 할래"))
        assertEquals(MenuChoice.Rules, MenuParser.parse("무슨 게임 있어?"))
        assertEquals(MenuChoice.Rules, MenuParser.parse("규칙 알려 줘"))
    }

    @Test
    fun `지금은 안 할 때`() {
        assertEquals(MenuChoice.Later, MenuParser.parse("나중에 할래"))
        assertEquals(MenuChoice.Later, MenuParser.parse("그만"))
        assertEquals(MenuChoice.Later, MenuParser.parse("안 할래"))
        // 게임 이름이 같이 있으면 게임이 우선
        assertEquals(MenuChoice.Start(GameKind.WORD_CHAIN, null), MenuParser.parse("그만하고 끝말잇기 하자"))
    }

    @Test
    fun `게임과 상관없는 차 안 대화는 고르지 않는다`() {
        assertEquals(MenuChoice.Unknown, MenuParser.parse("오늘 점심 뭐 먹지"))
        assertEquals(MenuChoice.Unknown, MenuParser.parse("에어컨 좀 켜 줘"))
        assertEquals(MenuChoice.Unknown, MenuParser.parse(""))
    }
}
