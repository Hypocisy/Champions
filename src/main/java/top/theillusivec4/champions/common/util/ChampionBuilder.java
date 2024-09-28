package top.theillusivec4.champions.common.util;

import com.google.common.collect.ImmutableSortedMap;
import net.minecraft.core.Holder;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import top.theillusivec4.champions.Champions;
import top.theillusivec4.champions.api.AffixCategory;
import top.theillusivec4.champions.api.IAffix;
import top.theillusivec4.champions.api.IChampion;
import top.theillusivec4.champions.common.affix.core.AffixManager;
import top.theillusivec4.champions.common.affix.core.AffixManager.AffixSettings;
import top.theillusivec4.champions.common.config.ChampionsConfig;
import top.theillusivec4.champions.common.rank.Rank;
import top.theillusivec4.champions.common.rank.RankManager;
import top.theillusivec4.champions.common.util.EntityManager.EntitySettings;

import java.util.*;

public class ChampionBuilder {

  private static final RandomSource RAND = RandomSource.createNewThreadLocalInstance();

  public static void spawn(final IChampion champion) {

    if (ChampionData.read(champion)) {
      return;
    }
    LivingEntity entity = champion.getLivingEntity();
    Rank newRank = ChampionBuilder.createRank(entity);
    champion.getServer().setRank(newRank);
    ChampionBuilder.applyGrowth(entity, newRank.getGrowthFactor());
    List<IAffix> newAffixes = ChampionBuilder.createAffixes(newRank, champion);
    champion.getServer().setAffixes(newAffixes);
    newAffixes.forEach(affix -> affix.onInitialSpawn(champion));
  }

  public static void spawnPreset(final IChampion champion, int tier, List<IAffix> affixes) {
    LivingEntity entity = champion.getLivingEntity();
    Rank newRank = RankManager.getRank(tier);
    champion.getServer().setRank(newRank);
    ChampionBuilder.applyGrowth(entity, newRank.getGrowthFactor());
    affixes = affixes.isEmpty() ? ChampionBuilder.createAffixes(newRank, champion) : affixes;
    champion.getServer().setAffixes(affixes);
    affixes.forEach(affix -> affix.onInitialSpawn(champion));
  }

  public static List<IAffix> createAffixes(final Rank rank, final IChampion champion) {
    int size = rank.getNumAffixes();
    List<IAffix> affixesToAdd = new ArrayList<>();
    Optional<EntitySettings> entitySettings = EntityManager
      .getSettings(champion.getLivingEntity().getType());

    if (size > 0) {
      entitySettings.ifPresent(settings -> {

        if (settings.presetAffixes != null) {
          affixesToAdd.addAll(settings.presetAffixes);
        }
      });
      rank.getPresetAffixes().forEach(affix -> {

        if (!affixesToAdd.contains(affix)) {
          affixesToAdd.add(affix);
        }
      });
    }
    Map<AffixCategory, List<IAffix>> allAffixes = Champions.API.getCategoryMap();
    Map<AffixCategory, List<IAffix>> validAffixes = new HashMap<>();

    for (AffixCategory category : Champions.API.getCategories()) {
      validAffixes.put(category, new ArrayList<>());
    }
    allAffixes.forEach((k, v) -> validAffixes.get(k).addAll(v.stream().filter(affix -> {
      Optional<AffixSettings> settings = AffixManager.getSettings(affix.getIdentifier());
      return !affixesToAdd.contains(affix) && entitySettings
        .map(entitySettings1 -> entitySettings1.canApply(affix)).orElse(true) && settings
        .map(affixSettings -> affixSettings.canApply(champion)).orElse(true) && affix
        .canApply(champion);
    }).toList()));
    List<IAffix> randomList = new ArrayList<>();
    validAffixes.forEach((k, v) -> randomList.addAll(v));

    while (!randomList.isEmpty() && affixesToAdd.size() < size) {
      int randomIndex = RAND.nextInt(randomList.size());
      IAffix randomAffix = randomList.get(randomIndex);

      if (affixesToAdd.stream().allMatch(affix -> affix.isCompatible(randomAffix) && (
        randomAffix.getCategory() == AffixCategory.OFFENSE || (affix.getCategory() != randomAffix
          .getCategory())))) {
        affixesToAdd.add(randomAffix);
      }
      randomList.remove(randomIndex);
    }
    return affixesToAdd;
  }

  public static Rank createRank(final LivingEntity livingEntity) {

    if (ChampionHelper.isPotential(livingEntity)) {
      return RankManager.getLowestRank();
    }
    ImmutableSortedMap<Integer, Rank> ranks = RankManager.getRanks();

    if (ranks.isEmpty()) {
      Champions.LOGGER.error(
        "No rank configuration found! Please check the 'champions-ranks.toml' file in the 'serverconfigs'.");
      return RankManager.getLowestRank();
    }
    Integer[] tierRange = new Integer[]{null, null};
    EntityManager.getSettings(livingEntity.getType()).ifPresent(entitySettings -> {
      tierRange[0] = entitySettings.minTier;
      tierRange[1] = entitySettings.maxTier;
    });
    Integer firstTier = tierRange[0] != null ? tierRange[0] : ranks.firstKey();
    int maxTier = tierRange[1] != null ? tierRange[1] : -1;
    Iterator<Integer> iter = ranks.navigableKeySet().tailSet(firstTier, false).iterator();
    Rank result = ranks.get(firstTier);

    if (result == null) {
      Champions.LOGGER.error("Tier {} cannot be found in {}! Assigning lowest available rank to {}",
        firstTier, ranks, livingEntity);
      return RankManager.getLowestRank();
    }

    while (iter.hasNext() && (result.getTier() < maxTier || maxTier == -1)) {
      Rank rank = ranks.get(iter.next());

      if (rank == null) {
        return result;
      }
      float chance = rank.getChance();


      if (RAND.nextFloat() < chance) {
        result = rank;
      } else {
        return result;
      }
    }
    return result;
  }

  public static void applyGrowth(final LivingEntity livingEntity, float growthFactor) {

    if (growthFactor < 1) {
      return;
    }
    grow(livingEntity, Attributes.MAX_HEALTH, ChampionsConfig.healthGrowth * growthFactor,
      AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
    livingEntity.setHealth(livingEntity.getMaxHealth());
    grow(livingEntity, Attributes.ATTACK_DAMAGE, ChampionsConfig.attackGrowth * growthFactor,
      AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
    grow(livingEntity, Attributes.ARMOR, ChampionsConfig.armorGrowth * growthFactor,
      AttributeModifier.Operation.ADD_VALUE);
    grow(livingEntity, Attributes.ARMOR_TOUGHNESS , ChampionsConfig.toughnessGrowth * growthFactor,
      AttributeModifier.Operation.ADD_VALUE);
    grow(livingEntity, Attributes.KNOCKBACK_RESISTANCE ,
      ChampionsConfig.knockbackResistanceGrowth * growthFactor,
      AttributeModifier.Operation.ADD_VALUE);
  }

  private static void grow(
    final LivingEntity livingEntity, Holder<Attribute> attribute, double amount,
    AttributeModifier.Operation operation) {
    AttributeInstance attributeInstance = livingEntity.getAttribute(attribute);

    if (attributeInstance != null) {
      double oldMax = attributeInstance.getBaseValue();
      double newMax = switch (operation) {
        case ADD_VALUE -> oldMax + amount;
        case ADD_MULTIPLIED_BASE -> oldMax * amount;
        case ADD_MULTIPLIED_TOTAL -> oldMax * (1 + amount);
      };
      attributeInstance.setBaseValue(newMax);
    }
  }

  public static void copy(IChampion oldChampion, IChampion newChampion) {
    IChampion.Server oldServer = oldChampion.getServer();
    IChampion.Server newServer = newChampion.getServer();
    Rank rank = oldServer.getRank().orElse(RankManager.getLowestRank());
    newServer.setRank(rank);
    ChampionBuilder.applyGrowth(newChampion.getLivingEntity(), rank.getGrowthFactor());
    List<IAffix> oldAffixes = oldChampion.getServer().getAffixes();
    newServer.setAffixes(oldAffixes);
    oldAffixes.forEach(affix -> affix.onInitialSpawn(newChampion));
  }
}
