package carpet.script.api;

import carpet.script.CarpetContext;
import carpet.script.Expression;
import carpet.script.argument.FunctionArgument;
import carpet.script.exception.InternalExpressionException;
import carpet.script.exception.ThrowStatement;
import carpet.script.exception.Throwables;
import carpet.script.utils.InputValidator;
import carpet.script.utils.RecipeHelper;
import carpet.script.value.BooleanValue;
import carpet.script.value.EntityValue;
import carpet.script.value.FormattedTextValue;
import carpet.script.value.FunctionValue;
import carpet.script.value.ListValue;
import carpet.script.value.NBTSerializableValue;
import carpet.script.value.NumericValue;
import carpet.script.value.ScreenValue;
import carpet.script.value.StringValue;
import carpet.script.value.Value;
import carpet.script.value.ValueConversions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.PlacementInfo;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.item.crafting.SingleItemRecipe;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.world.phys.Vec3;

public class Inventories
{
    private static final Logger LOGGER = LoggerFactory.getLogger(Inventories.class);
    public static void apply(Expression expression)
    {
        expression.addContextFunction("stack_limit", 1, (c, t, lv) ->
                new NumericValue(NBTSerializableValue.parseItem(lv.get(0).getString(), ((CarpetContext) c).registryAccess()).getMaxStackSize()));

        expression.addContextFunction("item_category", -1, (c, t, lv) -> {
            c.host.issueDeprecation("item_category in 1.19.3+");
            return Value.NULL;
        });

        expression.addContextFunction("item_list", -1, (c, t, lv) ->
        {
            CarpetContext cc = (CarpetContext) c;
            Registry<Item> items = cc.registry(Registries.ITEM);
            if (lv.isEmpty())
            {
                return ListValue.wrap(items.listElements().map(itemReference -> ValueConversions.of(itemReference.key().location())));
            }
            String tag = lv.get(0).getString();
            Optional<HolderSet.Named<Item>> itemTag = items.get(TagKey.create(Registries.ITEM, InputValidator.identifierOf(tag)));
            return itemTag.isEmpty() ? Value.NULL : ListValue.wrap(itemTag.get().stream().map(b -> items.getKey(b.value())).filter(Objects::nonNull).map(ValueConversions::of));
        });

        expression.addContextFunction("item_tags", -1, (c, t, lv) ->
        {
            CarpetContext cc = (CarpetContext) c;

            Registry<Item> blocks = cc.registry(Registries.ITEM);
            if (lv.isEmpty())
            {
                return ListValue.wrap(blocks.getTags().map(ValueConversions::of));
            }
            Item item = NBTSerializableValue.parseItem(lv.get(0).getString(), cc.registryAccess()).getItem();
            if (lv.size() == 1)
            {
                return ListValue.wrap(blocks.getTags().filter(e -> e.stream().anyMatch(h -> (h.value() == item))).map(ValueConversions::of));
            }
            String tag = lv.get(1).getString();
            Optional<HolderSet.Named<Item>> tagSet = blocks.get(TagKey.create(Registries.ITEM, InputValidator.identifierOf(tag)));
            return tagSet.isEmpty() ? Value.NULL : BooleanValue.of(tagSet.get().stream().anyMatch(h -> h.value() == item));
        });

        expression.addContextFunction("recipe_data", -1, (c, t, lv) ->
        {
            CarpetContext cc = (CarpetContext) c;
            if (lv.size() < 1)
            {
                throw new InternalExpressionException("'recipe_data' requires at least one argument");
            }
            String recipeName = lv.get(0).getString();
            RecipeType<? extends Recipe<?>> type = RecipeType.CRAFTING;
            if (lv.size() > 1)
            {
                String recipeType = lv.get(1).getString();
                type = cc.registry(Registries.RECIPE_TYPE).getValue(InputValidator.identifierOf(recipeType));
                if (type == null)
                {
                    throw new InternalExpressionException("Unknown recipe type: " + recipeType);
                }
            }
            List<Recipe<?>> recipes = RecipeHelper.getRecipesForOutput(cc.server().getRecipeManager(), type, InputValidator.identifierOf(recipeName), cc.level());
            if (recipes.isEmpty())
            {
                return Value.NULL;
            }
            List<Value> recipesOutput = new ArrayList<>();
            RegistryAccess regs = cc.registryAccess();
            ContextMap context = SlotDisplayContext.fromLevel(cc.level());
            for (Recipe<?> recipe : recipes)
            {
                List<Value> results = new ArrayList<>();
                //ItemStack result = recipe.display().forEach(); getResultItem(regs);
                recipe.display().forEach(rd -> rd.result().resolveForStacks(context).forEach(is -> results.add(ValueConversions.of(is, regs))));


                List<Value> ingredientValue = new ArrayList<>();
                for (int info : recipe.placementInfo().slotsToIngredientIndex())
                {
                    if (info == PlacementInfo.EMPTY_SLOT)
                    {
                        ingredientValue.add(Value.NULL);
                        continue;
                    }
                    int ingredientIndex = info;

                    List<Value> alternatives = new ArrayList<>();
                    // Fallback to old representative stacks exposed by Ingredient for display purposes
                    @SuppressWarnings("deprecation")
                    var _stream = recipe.placementInfo().ingredients().get(ingredientIndex).items();
                    _stream.forEach(holder -> alternatives.add(ValueConversions.of(holder.value(), regs)));
                    if (alternatives.isEmpty())
                    {
                        ingredientValue.add(Value.NULL);
                    }
                    else
                    {
                        ingredientValue.add(ListValue.wrap(alternatives));
                    }
                }


                Value recipeSpec;
                if (recipe instanceof ShapedRecipe shapedRecipe)
                {
                    recipeSpec = ListValue.of(
                            new StringValue("shaped"),
                            new NumericValue(shapedRecipe.getWidth()),
                            new NumericValue(shapedRecipe.getHeight())
                    );
                }
                else if (recipe instanceof ShapelessRecipe)
                {
                    recipeSpec = ListValue.of(new StringValue("shapeless"));
                }
                else if (recipe instanceof AbstractCookingRecipe abstractCookingRecipe)
                {
                    recipeSpec = ListValue.of(
                            new StringValue("smelting"),
                            new NumericValue(abstractCookingRecipe.cookingTime()),
                            new NumericValue(abstractCookingRecipe.experience())
                    );
                }
                else if (recipe instanceof SingleItemRecipe)
                {
                    recipeSpec = ListValue.of(new StringValue("cutting"));
                }
                else if (recipe instanceof CustomRecipe)
                {
                    recipeSpec = ListValue.of(new StringValue("special"));
                }
                else
                {
                    recipeSpec = ListValue.of(new StringValue("custom"));
                }

                recipesOutput.add(ListValue.of(ListValue.wrap(results), ListValue.wrap(ingredientValue), recipeSpec));
            }
            return ListValue.wrap(recipesOutput);
        });

        expression.addContextFunction("crafting_remaining_item", 1, (c, t, v) ->
        {
            String itemStr = v.get(0).getString();
            ResourceLocation id = InputValidator.identifierOf(itemStr);
            CarpetContext cc = (CarpetContext) c;
            Registry<Item> registry = cc.registry(Registries.ITEM);
            Item item = registry.getOptional(id).orElseThrow(() -> new ThrowStatement(itemStr, Throwables.UNKNOWN_ITEM));
            ItemStack reminder = item.getCraftingRemainder();
            return reminder.isEmpty() ? Value.NULL : ValueConversions.of(reminder, cc.registryAccess());
        });

        expression.addContextFunction("inventory_size", -1, (c, t, lv) ->
        {
            CarpetContext cc = (CarpetContext) c;
            NBTSerializableValue.InventoryLocator inventoryLocator = NBTSerializableValue.locateInventory(cc, lv, 0);
            return inventoryLocator == null ? Value.NULL : new NumericValue(inventoryLocator.inventory().getContainerSize());
        });

        expression.addContextFunction("inventory_has_items", -1, (c, t, lv) ->
        {
            CarpetContext cc = (CarpetContext) c;
            NBTSerializableValue.InventoryLocator inventoryLocator = NBTSerializableValue.locateInventory(cc, lv, 0);
            return inventoryLocator == null ? Value.NULL : BooleanValue.of(!inventoryLocator.inventory().isEmpty());
        });

        //inventory_get(<b, e>, <n>) -> item_triple
        expression.addContextFunction("inventory_get", -1, (c, t, lv) ->
        {
            CarpetContext cc = (CarpetContext) c;
            NBTSerializableValue.InventoryLocator inventoryLocator = NBTSerializableValue.locateInventory(cc, lv, 0);
            if (inventoryLocator == null)
            {
                return Value.NULL;
            }
            RegistryAccess regs = cc.registryAccess();
            if (lv.size() == inventoryLocator.offset())
            {
                List<Value> fullInventory = new ArrayList<>();
                for (int i = 0, maxi = inventoryLocator.inventory().getContainerSize(); i < maxi; i++)
                {
                    fullInventory.add(ValueConversions.of(inventoryLocator.inventory().getItem(i), regs));
                }
                return ListValue.wrap(fullInventory);
            }
            int slot = (int) NumericValue.asNumber(lv.get(inventoryLocator.offset())).getLong();
            slot = NBTSerializableValue.validateSlot(slot, inventoryLocator.inventory());
            return slot == inventoryLocator.inventory().getContainerSize()
                    ? Value.NULL
                    : ValueConversions.of(inventoryLocator.inventory().getItem(slot), regs);
        });

        //inventory_set(<b,e>, <n>, <count>, <item>, <nbt>)
        expression.addContextFunction("inventory_set", -1, (c, t, lv) ->
        {
            CarpetContext cc = (CarpetContext) c;
            NBTSerializableValue.InventoryLocator inventoryLocator = NBTSerializableValue.locateInventory(cc, lv, 0);
            if (inventoryLocator == null)
            {
                LOGGER.warn("inventory_set called with invalid inventory locator");
                return Value.NULL;
            }
            if (lv.size() < inventoryLocator.offset() + 2)
            {
                String errorMsg = "'inventory_set' requires at least slot number and new stack size, and optional new item. " +
                    "Usage: inventory_set(player, 'equipment', slot, count, [item], [nbt])";
                LOGGER.warn("inventory_set called with insufficient arguments: {} args provided, minimum {} required", 
                    lv.size(), inventoryLocator.offset() + 2);
                throw new InternalExpressionException(errorMsg);
            }
            
            try {
                // Enhanced slot validation for equipment inventory
                Value slotValue = lv.get(inventoryLocator.offset());
                int slot;
                if (inventoryLocator.inventory() instanceof carpet.script.utils.EquipmentInventory) {
                    // Handle both numeric and string slot parameters for equipment
                    if (slotValue instanceof NumericValue) {
                        slot = (int) ((NumericValue) slotValue).getLong();
                        // Validate equipment slot number (0-5)
                        try {
                            carpet.script.utils.EquipmentValidator.validateSlotNumber(slot);
                        } catch (InternalExpressionException e) {
                            LOGGER.warn("Invalid equipment slot number {} in inventory_set", slot);
                            throw e;
                        }
                    } else {
                        // Handle string slot names for equipment
                        String slotName = slotValue.getString();
                        try {
                            net.minecraft.world.entity.EquipmentSlot equipmentSlot = carpet.script.utils.EquipmentValidator.validateSlotName(slotName);
                            // Convert EquipmentSlot back to slot number for EquipmentInventory
                            slot = carpet.script.utils.EquipmentValidator.getSlotNumber(equipmentSlot);
                        } catch (InternalExpressionException e) {
                            LOGGER.warn("Invalid equipment slot name '{}' in inventory_set", slotName);
                            throw e;
                        }
                    }
                } else {
                    // Regular inventory slot validation
                    try {
                        slot = (int) NumericValue.asNumber(slotValue).getLong();
                        slot = NBTSerializableValue.validateSlot(slot, inventoryLocator.inventory());
                    } catch (Exception e) {
                        String errorMsg = String.format("Invalid slot parameter in inventory_set: %s. Expected numeric slot index.", slotValue.getString());
                        LOGGER.warn("Invalid slot parameter in inventory_set: {}", slotValue.getString());
                        throw new InternalExpressionException(errorMsg);
                    }
                }
                
                if (slot >= inventoryLocator.inventory().getContainerSize())
                {
                    LOGGER.warn("Slot {} is out of bounds for inventory size {}", slot, inventoryLocator.inventory().getContainerSize());
                    return Value.NULL;
                }
                
                OptionalInt count = OptionalInt.empty();
                Value countVal = lv.get(inventoryLocator.offset() + 1);
                if (!countVal.isNull())
                {
                    try {
                        int countValue = (int) NumericValue.asNumber(countVal).getLong();
                        if (countValue < 0) {
                            String errorMsg = "Item count cannot be negative: " + countValue;
                            LOGGER.warn("Negative item count {} provided in inventory_set", countValue);
                            throw new InternalExpressionException(errorMsg);
                        }
                        count = OptionalInt.of(countValue);
                    } catch (Exception e) {
                        String errorMsg = "Invalid count parameter in inventory_set: " + countVal.getString() + ". Expected numeric value.";
                        LOGGER.warn("Invalid count parameter in inventory_set: {}", countVal.getString());
                        throw new InternalExpressionException(errorMsg);
                    }
                }
                
                RegistryAccess regs = cc.registryAccess();
                if (count.isPresent() && count.getAsInt() == 0)
                {
                    // clear slot
                    ItemStack removedStack = inventoryLocator.inventory().removeItemNoUpdate(slot);
                    syncPlayerInventory(inventoryLocator);
                    LOGGER.debug("Cleared slot {} in inventory_set", slot);
                    return ValueConversions.of(removedStack, regs);
                }
                if (lv.size() < inventoryLocator.offset() + 3)
                {
                    ItemStack previousStack = inventoryLocator.inventory().getItem(slot);
                    ItemStack newStack = previousStack.copy();
                    count.ifPresent(newStack::setCount);
                    inventoryLocator.inventory().setItem(slot, newStack);
                    syncPlayerInventory(inventoryLocator);
                    LOGGER.debug("Updated item count in slot {} to {}", slot, count.orElse(-1));
                    return ValueConversions.of(previousStack, regs);
                }
                
                CompoundTag nbt = null; // skipping one argument, item name
                if (lv.size() > inventoryLocator.offset() + 3)
                {
                    Value nbtValue = lv.get(inventoryLocator.offset() + 3);
                    try {
                        if (nbtValue instanceof NBTSerializableValue nbtsv)
                        {
                            nbt = nbtsv.getCompoundTag();
                        }
                        else if (!nbtValue.isNull())
                        {
                            nbt = new NBTSerializableValue(nbtValue.getString()).getCompoundTag();
                        }
                    } catch (Exception e) {
                        String errorMsg = "Invalid NBT data in inventory_set: " + e.getMessage();
                        LOGGER.warn("Invalid NBT data in inventory_set: {}", e.getMessage());
                        throw new InternalExpressionException(errorMsg);
                    }
                }
                
                // Validate item name for equipment inventory
                String itemName = lv.get(inventoryLocator.offset() + 2).getString();
                if (inventoryLocator.inventory() instanceof carpet.script.utils.EquipmentInventory) {
                    try {
                        carpet.script.utils.EquipmentValidator.validateItemName(itemName, cc);
                    } catch (InternalExpressionException e) {
                        LOGGER.warn("Invalid item name '{}' for equipment inventory in inventory_set", itemName);
                        throw e;
                    }
                }
                
                ItemStack newitem;
                try {
                    newitem = NBTSerializableValue.parseItem(itemName, nbt, cc.registryAccess());
                    if (newitem.isEmpty()) {
                        String errorMsg = "Failed to create item from name: " + itemName;
                        LOGGER.warn("Failed to create item from name '{}' in inventory_set", itemName);
                        throw new InternalExpressionException(errorMsg);
                    }
                } catch (Exception e) {
                    String errorMsg = "Invalid item name in inventory_set: " + itemName + ". " + e.getMessage();
                    LOGGER.warn("Invalid item name '{}' in inventory_set: {}", itemName, e.getMessage());
                    throw new InternalExpressionException(errorMsg);
                }
                
                count.ifPresent(newitem::setCount);
                ItemStack previousStack = inventoryLocator.inventory().getItem(slot);
                inventoryLocator.inventory().setItem(slot, newitem);
                syncPlayerInventory(inventoryLocator);

                LOGGER.debug("Set item '{}' in slot {} with count {}", itemName, slot, newitem.getCount());
                return ValueConversions.of(previousStack, regs);
                
            } catch (InternalExpressionException e) {
                // Re-throw InternalExpressionException as-is
                throw e;
            } catch (Exception e) {
                String errorMsg = "Unexpected error in inventory_set: " + e.getMessage();
                LOGGER.error("Unexpected error in inventory_set: {}", e.getMessage(), e);
                throw new InternalExpressionException(errorMsg);
            }
        });

        //inventory_find(<b, e>, <item> or null (first empty slot), <start_from=0> ) -> <N> or null
        expression.addContextFunction("inventory_find", -1, (c, t, lv) ->
        {
            CarpetContext cc = (CarpetContext) c;
            NBTSerializableValue.InventoryLocator inventoryLocator = NBTSerializableValue.locateInventory(cc, lv, 0);
            if (inventoryLocator == null)
            {
                return Value.NULL;
            }
            ItemStack itemArg = null;
            if (lv.size() > inventoryLocator.offset())
            {
                Value secondArg = lv.get(inventoryLocator.offset());
                if (!secondArg.isNull())
                {
                    itemArg = NBTSerializableValue.parseItem(secondArg.getString(), cc.registryAccess());
                }
            }
            int startIndex = 0;
            if (lv.size() > inventoryLocator.offset() + 1)
            {
                startIndex = (int) NumericValue.asNumber(lv.get(inventoryLocator.offset() + 1)).getLong();
            }
            startIndex = NBTSerializableValue.validateSlot(startIndex, inventoryLocator.inventory());
            for (int i = startIndex, maxi = inventoryLocator.inventory().getContainerSize(); i < maxi; i++)
            {
                ItemStack stack = inventoryLocator.inventory().getItem(i);
                if ((itemArg == null && stack.isEmpty()) || (itemArg != null && itemArg.getItem().equals(stack.getItem())))
                {
                    return new NumericValue(i);
                }
            }
            return Value.NULL;
        });

        //inventory_remove(<b, e>, <item>, <amount=1>) -> bool
        expression.addContextFunction("inventory_remove", -1, (c, t, lv) ->
        {
            CarpetContext cc = (CarpetContext) c;
            NBTSerializableValue.InventoryLocator inventoryLocator = NBTSerializableValue.locateInventory(cc, lv, 0);
            if (inventoryLocator == null)
            {
                return Value.NULL;
            }
            if (lv.size() <= inventoryLocator.offset())
            {
                throw new InternalExpressionException("'inventory_remove' requires at least an item to be removed");
            }
            ItemStack searchItem = NBTSerializableValue.parseItem(lv.get(inventoryLocator.offset()).getString(), cc.registryAccess());
            int amount = 1;
            if (lv.size() > inventoryLocator.offset() + 1)
            {
                amount = (int) NumericValue.asNumber(lv.get(inventoryLocator.offset() + 1)).getLong();
            }
            // not enough
            if (((amount == 1) && (!inventoryLocator.inventory().hasAnyOf(Set.of(searchItem.getItem()))))
                    || (inventoryLocator.inventory().countItem(searchItem.getItem()) < amount))
            {
                return Value.FALSE;
            }
            for (int i = 0, maxi = inventoryLocator.inventory().getContainerSize(); i < maxi; i++)
            {
                ItemStack stack = inventoryLocator.inventory().getItem(i);
                if (stack.isEmpty() || !stack.getItem().equals(searchItem.getItem()))
                {
                    continue;
                }
                int left = stack.getCount() - amount;
                if (left > 0)
                {
                    stack.setCount(left);
                    inventoryLocator.inventory().setItem(i, stack);
                    syncPlayerInventory(inventoryLocator);
                    return Value.TRUE;
                }
                inventoryLocator.inventory().removeItemNoUpdate(i);
                syncPlayerInventory(inventoryLocator);
                amount -= stack.getCount();
            }
            if (amount > 0)
            {
                throw new InternalExpressionException("Something bad happened - cannot pull all items from inventory");
            }
            return Value.TRUE;
        });

        //inventory_drop(<b, e>, <n>, <amount=1, 0-whatever's there>) -> entity_item (and sets slot) or null if cannot
        expression.addContextFunction("drop_item", -1, (c, t, lv) ->
        {
            CarpetContext cc = (CarpetContext) c;
            NBTSerializableValue.InventoryLocator inventoryLocator = NBTSerializableValue.locateInventory(cc, lv, 0);
            if (inventoryLocator == null)
            {
                return Value.NULL;
            }
            if (lv.size() == inventoryLocator.offset())
            {
                throw new InternalExpressionException("Slot number is required for inventory_drop");
            }
            int slot = (int) NumericValue.asNumber(lv.get(inventoryLocator.offset())).getLong();
            slot = NBTSerializableValue.validateSlot(slot, inventoryLocator.inventory());
            if (slot == inventoryLocator.inventory().getContainerSize())
            {
                return Value.NULL;
            }
            int amount = 0;
            if (lv.size() > inventoryLocator.offset() + 1)
            {
                amount = (int) NumericValue.asNumber(lv.get(inventoryLocator.offset() + 1)).getLong();
            }
            if (amount < 0)
            {
                throw new InternalExpressionException("Cannot throw negative number of items");
            }
            ItemStack stack = inventoryLocator.inventory().getItem(slot);
            if (stack == null || stack.isEmpty())
            {
                return Value.ZERO;
            }
            if (amount == 0)
            {
                amount = stack.getCount();
            }
            ItemStack droppedStack = inventoryLocator.inventory().removeItem(slot, amount);
            if (droppedStack.isEmpty())
            {
                return Value.ZERO;
            }
            Object owner = inventoryLocator.owner();
            ItemEntity item;
            if (owner instanceof Player player)
            {
                item = player.drop(droppedStack, false, true);
                if (item == null)
                {
                    return Value.ZERO;
                }
            }
            else if (owner instanceof LivingEntity livingEntity)
            {
                // stolen from LookTargetUtil.give((VillagerEntity)owner, droppedStack, (LivingEntity) owner);
                double dropY = livingEntity.getY() - 0.30000001192092896D + livingEntity.getEyeHeight();
                item = new ItemEntity(livingEntity.level(), livingEntity.getX(), dropY, livingEntity.getZ(), droppedStack);
                Vec3 vec3d = livingEntity.getViewVector(1.0F).normalize().scale(0.3);//  new Vec3d(0, 0.3, 0);
                item.setDeltaMovement(vec3d);
                item.setDefaultPickUpDelay();
                cc.level().addFreshEntity(item);
            }
            else
            {
                Vec3 point = Vec3.atCenterOf(inventoryLocator.position()); //pos+0.5v
                item = new ItemEntity(cc.level(), point.x, point.y, point.z, droppedStack);
                item.setDefaultPickUpDelay();
                cc.level().addFreshEntity(item);
            }
            return new NumericValue(item.getItem().getCount());
        });

        expression.addContextFunction("create_screen", -1, (c, t, lv) ->
        {
            if (lv.size() < 3)
            {
                throw new InternalExpressionException("'create_screen' requires at least three arguments");
            }
            Value playerValue = lv.get(0);
            ServerPlayer player = EntityValue.getPlayerByValue(((CarpetContext) c).server(), playerValue);
            if (player == null)
            {
                throw new InternalExpressionException("'create_screen' requires a valid online player as the first argument.");
            }
            String type = lv.get(1).getString();
            Component name = FormattedTextValue.getTextByValue(lv.get(2));
            FunctionValue function = null;
            if (lv.size() > 3)
            {
                function = FunctionArgument.findIn(c, expression.module, lv, 3, true, false).function;
            }

            return new ScreenValue(player, type, name, function, c);
        });

        expression.addContextFunction("close_screen", 1, (c, t, lv) ->
        {
            Value value = lv.get(0);
            if (!(value instanceof ScreenValue screenValue))
            {
                throw new InternalExpressionException("'close_screen' requires a screen value as the first argument.");
            }
            if (!screenValue.isOpen())
            {
                return Value.FALSE;
            }
            screenValue.close();
            return Value.TRUE;
        });

        expression.addContextFunction("screen_property", -1, (c, t, lv) ->
        {
            if (lv.size() < 2)
            {
                throw new InternalExpressionException("'screen_property' requires at least a screen and a property name");
            }
            if (!(lv.get(0) instanceof ScreenValue screenValue))
            {
                throw new InternalExpressionException("'screen_property' requires a screen value as the first argument");
            }
            String propertyName = lv.get(1).getString();
            return lv.size() >= 3
                    ? screenValue.modifyProperty(propertyName, lv.subList(2, lv.size()))
                    : screenValue.queryProperty(propertyName);
        });

        // Enhanced Scarpet equipment functions
        
        //equipment_get(<player>, <slot>) -> item_triple or null
        expression.addContextFunction("equipment_get", -1, (c, t, lv) ->
        {
            CarpetContext cc = (CarpetContext) c;
            if (lv.size() < 2)
            {
                throw new InternalExpressionException("'equipment_get' requires a player and slot parameter. " +
                    "Usage: equipment_get(player, slot) where slot can be a number (0-5) or name ('head', 'chest', etc.)");
            }
            
            try {
                // Get player and create equipment inventory locator
                List<Value> inventoryArgs = List.of(lv.get(0), new StringValue("equipment"));
                NBTSerializableValue.InventoryLocator inventoryLocator = NBTSerializableValue.locateInventory(cc, inventoryArgs, 0);
                if (inventoryLocator == null)
                {
                    LOGGER.warn("equipment_get called with invalid player: {}", lv.get(0).getString());
                    return Value.NULL;
                }
                
                // Validate and convert slot parameter
                Value slotValue = lv.get(1);
                int slot;
                if (slotValue instanceof NumericValue) {
                    int slotNumber = (int) ((NumericValue) slotValue).getLong();
                    carpet.script.utils.EquipmentValidator.validateSlotNumber(slotNumber);
                    slot = slotNumber;
                } else {
                    String slotName = slotValue.getString();
                    net.minecraft.world.entity.EquipmentSlot equipmentSlot = carpet.script.utils.EquipmentValidator.validateSlotName(slotName);
                    slot = carpet.script.utils.EquipmentValidator.getSlotNumber(equipmentSlot);
                }
                
                if (slot >= inventoryLocator.inventory().getContainerSize())
                {
                    LOGGER.warn("equipment_get: slot {} is out of bounds for equipment inventory", slot);
                    return Value.NULL;
                }
                
                ItemStack item = inventoryLocator.inventory().getItem(slot);
                RegistryAccess regs = cc.registryAccess();
                LOGGER.debug("equipment_get retrieved item from slot {}: {}", slot, item.isEmpty() ? "empty" : item.getDisplayName().getString());
                return ValueConversions.of(item, regs);
                
            } catch (InternalExpressionException e) {
                LOGGER.warn("equipment_get validation error: {}", e.getMessage());
                throw e;
            } catch (Exception e) {
                String errorMsg = "Unexpected error in equipment_get: " + e.getMessage();
                LOGGER.error("Unexpected error in equipment_get: {}", e.getMessage(), e);
                throw new InternalExpressionException(errorMsg);
            }
        });

        //equipment_clear(<player>, <slot>) -> item_triple (previous item) or null
        expression.addContextFunction("equipment_clear", -1, (c, t, lv) ->
        {
            CarpetContext cc = (CarpetContext) c;
            if (lv.size() < 2)
            {
                throw new InternalExpressionException("'equipment_clear' requires a player and slot parameter. " +
                    "Usage: equipment_clear(player, slot) where slot can be a number (0-5) or name ('head', 'chest', etc.)");
            }
            
            try {
                // Get player and create equipment inventory locator
                List<Value> inventoryArgs = List.of(lv.get(0), new StringValue("equipment"));
                NBTSerializableValue.InventoryLocator inventoryLocator = NBTSerializableValue.locateInventory(cc, inventoryArgs, 0);
                if (inventoryLocator == null)
                {
                    LOGGER.warn("equipment_clear called with invalid player: {}", lv.get(0).getString());
                    return Value.NULL;
                }
                
                // Validate and convert slot parameter
                Value slotValue = lv.get(1);
                int slot;
                if (slotValue instanceof NumericValue) {
                    int slotNumber = (int) ((NumericValue) slotValue).getLong();
                    carpet.script.utils.EquipmentValidator.validateSlotNumber(slotNumber);
                    slot = slotNumber;
                } else {
                    String slotName = slotValue.getString();
                    net.minecraft.world.entity.EquipmentSlot equipmentSlot = carpet.script.utils.EquipmentValidator.validateSlotName(slotName);
                    slot = carpet.script.utils.EquipmentValidator.getSlotNumber(equipmentSlot);
                }
                
                if (slot >= inventoryLocator.inventory().getContainerSize())
                {
                    LOGGER.warn("equipment_clear: slot {} is out of bounds for equipment inventory", slot);
                    return Value.NULL;
                }
                
                // Remove item from slot
                ItemStack previousItem = inventoryLocator.inventory().removeItemNoUpdate(slot);
                syncPlayerInventory(inventoryLocator);
                
                RegistryAccess regs = cc.registryAccess();
                LOGGER.debug("equipment_clear removed item from slot {}: {}", slot, previousItem.isEmpty() ? "empty" : previousItem.getDisplayName().getString());
                return ValueConversions.of(previousItem, regs);
                
            } catch (InternalExpressionException e) {
                LOGGER.warn("equipment_clear validation error: {}", e.getMessage());
                throw e;
            } catch (Exception e) {
                String errorMsg = "Unexpected error in equipment_clear: " + e.getMessage();
                LOGGER.error("Unexpected error in equipment_clear: {}", e.getMessage(), e);
                throw new InternalExpressionException(errorMsg);
            }
        });

        //modify(<player>, 'equipment', <slot>, <item>, [<nbt>]) -> item_triple (previous item)
        expression.addContextFunction("modify", -1, (c, t, lv) ->
        {
            CarpetContext cc = (CarpetContext) c;
            if (lv.size() < 4)
            {
                throw new InternalExpressionException("'modify' with equipment requires at least player, 'equipment', slot, and item parameters. " +
                    "Usage: modify(player, 'equipment', slot, item, [nbt]) where slot can be a number (0-5) or name ('head', 'chest', etc.)");
            }
            
            // Check if this is an equipment modification
            if (lv.size() >= 2 && "equipment".equals(lv.get(1).getString()))
            {
                try {
                    // Get player and create equipment inventory locator
                    List<Value> inventoryArgs = List.of(lv.get(0), lv.get(1));
                    NBTSerializableValue.InventoryLocator inventoryLocator = NBTSerializableValue.locateInventory(cc, inventoryArgs, 0);
                    if (inventoryLocator == null)
                    {
                        LOGGER.warn("modify equipment called with invalid player: {}", lv.get(0).getString());
                        return Value.NULL;
                    }
                    
                    // Validate and convert slot parameter
                    Value slotValue = lv.get(2);
                    int slot;
                    if (slotValue instanceof NumericValue) {
                        int slotNumber = (int) ((NumericValue) slotValue).getLong();
                        carpet.script.utils.EquipmentValidator.validateSlotNumber(slotNumber);
                        slot = slotNumber;
                    } else {
                        String slotName = slotValue.getString();
                        net.minecraft.world.entity.EquipmentSlot equipmentSlot = carpet.script.utils.EquipmentValidator.validateSlotName(slotName);
                        slot = carpet.script.utils.EquipmentValidator.getSlotNumber(equipmentSlot);
                    }
                    
                    if (slot >= inventoryLocator.inventory().getContainerSize())
                    {
                        LOGGER.warn("modify equipment: slot {} is out of bounds for equipment inventory", slot);
                        return Value.NULL;
                    }
                    
                    // Get item name and validate
                    String itemName = lv.get(3).getString();
                    if (itemName == null || itemName.trim().isEmpty()) {
                        throw new InternalExpressionException("Item name cannot be empty in modify equipment function");
                    }
                    carpet.script.utils.EquipmentValidator.validateItemName(itemName, cc);
                    
                    // Handle NBT if provided
                    CompoundTag nbt = null;
                    if (lv.size() > 4)
                    {
                        Value nbtValue = lv.get(4);
                        try {
                            if (nbtValue instanceof NBTSerializableValue nbtsv)
                            {
                                nbt = nbtsv.getCompoundTag();
                            }
                            else if (!nbtValue.isNull())
                            {
                                nbt = new NBTSerializableValue(nbtValue.getString()).getCompoundTag();
                            }
                        } catch (Exception e) {
                            String errorMsg = "Invalid NBT data in modify equipment: " + e.getMessage();
                            LOGGER.warn("Invalid NBT data in modify equipment: {}", e.getMessage());
                            throw new InternalExpressionException(errorMsg);
                        }
                    }
                    
                    // Create new item stack with NBT support for enchanted equipment
                    ItemStack newItem;
                    try {
                        newItem = NBTSerializableValue.parseItem(itemName, nbt, cc.registryAccess());
                        if (newItem.isEmpty()) {
                            String errorMsg = "Failed to create item from name: " + itemName;
                            LOGGER.warn("Failed to create item from name '{}' in modify equipment", itemName);
                            throw new InternalExpressionException(errorMsg);
                        }
                    } catch (Exception e) {
                        String errorMsg = "Invalid item name in modify equipment: " + itemName + ". " + e.getMessage();
                        LOGGER.warn("Invalid item name '{}' in modify equipment: {}", itemName, e.getMessage());
                        throw new InternalExpressionException(errorMsg);
                    }
                    
                    // Set the item and sync
                    ItemStack previousItem = inventoryLocator.inventory().getItem(slot);
                    inventoryLocator.inventory().setItem(slot, newItem);
                    syncPlayerInventory(inventoryLocator);
                    
                    RegistryAccess regs = cc.registryAccess();
                    LOGGER.debug("modify equipment set item '{}' in slot {} with NBT support", itemName, slot);
                    return ValueConversions.of(previousItem, regs);
                    
                } catch (InternalExpressionException e) {
                    LOGGER.warn("modify equipment validation error: {}", e.getMessage());
                    throw e;
                } catch (Exception e) {
                    String errorMsg = "Unexpected error in modify equipment: " + e.getMessage();
                    LOGGER.error("Unexpected error in modify equipment: {}", e.getMessage(), e);
                    throw new InternalExpressionException(errorMsg);
                }
            }
            else
            {
                // This is not an equipment modification, delegate to existing modify logic if it exists
                // For now, we'll throw an exception since we're only implementing equipment modification
                throw new InternalExpressionException("'modify' function currently only supports equipment modifications. " +
                    "Usage: modify(player, 'equipment', slot, item, [nbt])");
            }
        });
    }

    private static void syncPlayerInventory(NBTSerializableValue.InventoryLocator inventory)
    {
        if (inventory.owner() instanceof ServerPlayer player && !inventory.isEnder() && !(inventory.inventory() instanceof ScreenValue.ScreenHandlerInventory))
        {
            try {
                player.containerMenu.broadcastChanges();
                
                // Special handling for equipment inventory synchronization
                if (inventory.inventory() instanceof carpet.script.utils.EquipmentInventory) {
                    // Force equipment synchronization for fake players
                    if (player instanceof carpet.patches.EntityPlayerMPFake fakePlayer) {
                        LOGGER.debug("Syncing equipment inventory for fake player: {}", player.getName().getString());
                        fakePlayer.syncEquipmentToClients();
                    } else {
                        LOGGER.debug("Syncing equipment inventory for real player: {}", player.getName().getString());
                    }
                } else {
                    LOGGER.debug("Syncing regular inventory for player: {}", player.getName().getString());
                }
            } catch (Exception e) {
                LOGGER.error("Failed to sync inventory for player {}: {}", player.getName().getString(), e.getMessage(), e);
                // Don't throw exception to avoid breaking the calling function
            }
        }
    }
}
