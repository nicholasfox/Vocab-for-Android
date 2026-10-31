package com.example.data.importer

import com.example.data.entity.Deck
import com.example.data.entity.Flashcard
import org.json.JSONArray
import org.json.JSONObject

data class SharedFolder(val name:String,val course:String,val description:String,val color:String,val cards:List<Flashcard>)

object FolderShare {
    private val fields=listOf("word","phonetic","partOfSpeech","definition","exampleSentence","exampleTranslation","notes")
    fun export(decks:List<Deck>,cards:List<Flashcard>,notes:Boolean=false):String = JSONObject()
        .put("format","vocab-folder-share").put("version",1).put("decks",JSONArray(decks.sortedBy{it.sortOrder}.map { deck ->
            JSONObject().put("name",deck.name).put("course",deck.category).put("description",deck.description).put("color",deck.colorHex)
                .put("cards",JSONArray(cards.filter{it.deckId==deck.id}.sortedByDescending{it.sortOrder}.map { card ->
                    JSONObject().put("word",card.word).put("phonetic",card.phonetic).put("partOfSpeech",card.partOfSpeech)
                        .put("definition",card.definition).put("exampleSentence",card.exampleSentence).put("exampleTranslation",card.exampleTranslation)
                        .put("notes",if(notes)card.notes else "")
                }))
        })).toString(2)
    fun parse(raw:String):List<SharedFolder>{
        require(raw.toByteArray(Charsets.UTF_8).size<=20*1024*1024){"分享档超过 20 MB"}
        val root=JSONObject(raw)
        require(root.optString("format")=="vocab-folder-share"&&root.optInt("version")==1){"请选择 Vocab 文件夹分享档，不是完整备份"}
        val decks=root.getJSONArray("decks");require(decks.length() in 1..200){"文件夹数量不符限制"};var total=0
        fun text(obj:JSONObject,key:String,limit:Int=20000):String{val value=obj.opt(key);require(value==null||value is String){"字段 $key 必须是文字"};return (value as? String).orEmpty().trim().also{require(it.length<=limit){"字段过长"}}}
        return (0 until decks.length()).map{index->val deck=decks.getJSONObject(index);val rows=deck.getJSONArray("cards");total+=rows.length();require(total<=20000){"单次最多 20,000 张卡片"}
            val cards=(0 until rows.length()).map{i->val row=rows.getJSONObject(i);val values=fields.associateWith{text(row,it,if(it=="word")200 else 20000)};require(!values.getValue("word").isBlank()&&!values.getValue("definition").isBlank()){"单词与解释不可空白"}
                Flashcard(deckId=0,word=values.getValue("word"),definition=values.getValue("definition"),phonetic=values.getValue("phonetic"),partOfSpeech=values.getValue("partOfSpeech"),exampleSentence=values.getValue("exampleSentence"),exampleTranslation=values.getValue("exampleTranslation"),notes=values.getValue("notes"))}
            val name=text(deck,"name",200);require(name.isNotBlank()){"文件夹名称不可空白"};val color=text(deck,"color",30)
            SharedFolder(name,text(deck,"course",200).ifBlank{"通用"},text(deck,"description"),color.takeIf{it.matches(Regex("#[0-9a-fA-F]{6}"))}?:"#426B63",cards)
        }
    }
}
