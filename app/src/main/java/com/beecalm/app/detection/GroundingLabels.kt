package com.beecalm.app.detection

/**
 * Decides which of the detector's 600 Open Images classes are good grounding anchors and how to say
 * them out loud. People, body parts and very broad categories ("Furniture", "Food") are left out:
 * naming a person in the room during a panic attack doesn't help, and "Furniture" is a vaguer
 * anchor than the chair it was drawn around.
 */
object GroundingLabels {

  private val excludedExact =
      setOf(
          "person", "man", "woman", "boy", "girl", "human body", "human face", "human head",
          "human hair", "human hand", "human arm", "human leg", "human foot", "human eye",
          "human ear", "human nose", "human mouth", "human beard", "clothing", "footwear",
          "fashion accessory", "furniture", "food", "animal", "mammal", "vehicle", "land vehicle",
          "tree", "plant", "building", "tool", "kitchen appliance", "home appliance",
          "office supplies", "sports equipment", "toy", "drink", "fast food", "snack",
          "baked goods", "dessert", "seafood", "vegetable", "fruit", "invertebrate", "insect",
          "reptile", "bird", "carnivore", "auto part", "window blind", "skyscraper", "tower",
          "house", "office building", "weapon", "rifle", "handgun", "shotgun", "knife",
          "dagger", "sword", "bomb", "missile", "tank",
      )

  /** Friendlier spoken names for a few detector labels. */
  private val spoken =
      mapOf(
          "mobile phone" to "phone",
          "houseplant" to "plant",
          "computer keyboard" to "keyboard",
          "computer mouse" to "mouse",
          "computer monitor" to "screen",
          "television" to "TV",
          "coffee cup" to "cup",
          "mug" to "mug",
          "studio couch" to "couch",
          "sofa bed" to "couch",
          "kitchen & dining room table" to "table",
          "porch" to "porch",
          "bottle opener" to "bottle opener",
          "remote control" to "remote",
          "digital clock" to "clock",
          "wall clock" to "clock",
          "alarm clock" to "clock",
          "loveseat" to "couch",
      )

  fun isGroundingObject(label: String): Boolean {
    val l = label.lowercase().trim()
    if (l.isEmpty() || l in excludedExact) return false
    if (l.startsWith("human ")) return false
    return true
  }

  fun spokenName(label: String): String {
    val l = label.lowercase().trim()
    return spoken[l] ?: l
  }
}
