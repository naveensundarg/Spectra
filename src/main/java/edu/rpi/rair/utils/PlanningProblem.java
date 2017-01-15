package edu.rpi.rair.utils;

import clojure.lang.Obj;
import com.naveensundarg.shadow.prover.representations.formula.Formula;
import com.naveensundarg.shadow.prover.representations.value.Value;
import com.naveensundarg.shadow.prover.representations.value.Variable;
import com.naveensundarg.shadow.prover.utils.CollectionUtils;
import com.naveensundarg.shadow.prover.utils.Reader;
import edu.rpi.rair.Action;
import edu.rpi.rair.State;
import us.bpsm.edn.Keyword;
import us.bpsm.edn.Symbol;
import us.bpsm.edn.parser.Parseable;
import us.bpsm.edn.parser.Parser;
import us.bpsm.edn.parser.Parsers;
import us.bpsm.edn.parser.Token;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.security.Key;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Created by naveensundarg on 1/13/17.
 */
public class PlanningProblem {

    private Set<Formula> background;
    private Set<Action> actions;
    private State start;
    private State goal;
    private String name;
    private Optional<Set<List<Action>>> expectedActionSequencesOpt;
    private Map<String, Action> actionMap;

    private static final Keyword BACKGROUND = Keyword.newKeyword("background");
    private static final Keyword START = Keyword.newKeyword("start");
    private static final Keyword GOAL = Keyword.newKeyword("goal");
    private static final Keyword NAME = Keyword.newKeyword("name");
    private static final Keyword ACTION = Keyword.newKeyword("actions");

    private static final Keyword PRECONDITIONS = Keyword.newKeyword("preconditions");
    private static final Keyword ADDITIONS = Keyword.newKeyword("additions");
    private static final Keyword DELETIONS = Keyword.newKeyword("deletions");

    private static final Symbol ACTION_DEFINER = Symbol.newSymbol("define-action");

    private static final Keyword EXPECTED_PLANS = Keyword.newKeyword("expected-plans");

    public PlanningProblem(String name, Set<Formula> background, State start, State goal, Set<Action> actions) {

        this.background = background;
        this.start = start;
        this.actions = actions;
        this.goal = goal;
        this.name = name;
        this.actionMap = CollectionUtils.newMap();
        this.expectedActionSequencesOpt = Optional.empty();
    }

    public PlanningProblem(String name, Set<Formula> background, State start, State goal, Set<Action> actions, Set<List<Action>> expectedActionSequences) {

        this.background = background;
        this.start = start;
        this.actions = actions;
        this.goal = goal;
        this.name = name;
        this.actionMap = CollectionUtils.newMap();
        this.expectedActionSequencesOpt = Optional.of(expectedActionSequences);
    }

    public static List<PlanningProblem> readFromFile(InputStream inputStream) throws Reader.ParsingException {

        Parseable parseable = Parsers.newParseable(new InputStreamReader(inputStream));
        Parser parser = Parsers.newParser(Parsers.defaultConfiguration());

        List<PlanningProblem> planningProblems = CollectionUtils.newEmptyList();

        Object nextValue = parser.nextValue(parseable);

        while (!nextValue.equals(Token.END_OF_INPUT)) {

            Map<?, ?> planningProblemSpec = (Map<?, ?>) nextValue;

            Set<Formula> background = readFrom((List<?>) planningProblemSpec.get(BACKGROUND));
            Set<Formula> start = readFrom((List<?>) planningProblemSpec.get(START));
            Set<Formula> goal = readFrom((List<?>) planningProblemSpec.get(GOAL));

            List<?> actionDefinitions = (List<?>) planningProblemSpec.get(ACTION);

            String name = planningProblemSpec.get(NAME).toString();
            Set<Action> actions = readActionsFrom(actionDefinitions);
            Map<String, Action> actionMap = CollectionUtils.newMap();

            actions.stream().forEach(action->{
                actionMap.put(action.getName(), action);
            });
            if(planningProblemSpec.containsKey(EXPECTED_PLANS)){
                List<?> plans = (List<?>) planningProblemSpec.get(EXPECTED_PLANS);

                Set<List<Action>> expectedActions = plans.stream().map(plan->{

                     List<?> instantActionList = (List<?>) plan;

                    List<Action> actionsList =  instantActionList.stream().map(x -> {
                        try {
                            return readInstantiatedAction(actionMap, x);
                        } catch (Reader.ParsingException e) {
                           return null;
                        }
                    }).collect(Collectors.toList());

                    if(actionsList.stream().anyMatch(Objects::isNull)){
                        return null;
                    } else {
                        return actionsList;
                    }

                }).collect(Collectors.toSet());


                 planningProblems.add(new PlanningProblem(name, background, State.initializeWith(start),
                    State.initializeWith(goal), actions, expectedActions));
            } else {

                 planningProblems.add(new PlanningProblem(name, background, State.initializeWith(start),
                    State.initializeWith(goal), actions));
            }



            nextValue = parser.nextValue(parseable);
        }

        return planningProblems;

    }



    private  static Action readInstantiatedAction(Map<String, Action> actionMap, Object instantiatedActionSpec) throws Reader.ParsingException {

        if(instantiatedActionSpec instanceof List<?>){

            List<?> instActionList = (List<?>) instantiatedActionSpec;
            String name = instActionList.get(0).toString();
            Action general = actionMap.get(name);

            List<Variable> variables = general.openVars();
            if(variables.size()!=instActionList.size()-1){

                throw new AssertionError("Not a proper instantiation of "+ name);

            }

            Map<Variable, Value> binding = CollectionUtils.newMap();
            for(int i = 1; i<instActionList.size(); i++){

                binding.put(variables.get(i-1), Reader.readLogicValue(instActionList.get(i)));
            }


            return general.instantiate(binding);
        } else {

            String name = instantiatedActionSpec.toString();

            if(actionMap.containsKey(name)){
               return actionMap.get(name);
            }
            else{
                return null;
            }
        }
    }

    private static Set<Action> readActionsFrom(List<?> actionSpecs) throws Reader.ParsingException {

        Set<Action> actions = actionSpecs.stream().map(spec -> {
            List<?> specBody = (List<?>) spec;
            if(!specBody.get(0).equals(ACTION_DEFINER)){

               return null;
            }
            String name = specBody.get(1).toString();
            List<Variable> vars = ((List<?>)specBody.get(2)).stream().map(x -> {
                try {
                    return Reader.readLogicValue(x);
                } catch (Reader.ParsingException e) {
                    e.printStackTrace();
                    return null;
                }
            }).map(x->(Variable)x).collect(Collectors.toList());

            if(vars.stream().anyMatch(Objects::isNull)){
                return null;
            }
            Map<?, ?> actionSpec = (Map<?, ?>) specBody.get(3);
            try {


                Set<Formula> preconditions = readFrom((List<?>) actionSpec.get(PRECONDITIONS));
                Set<Formula> additions = readFrom((List<?>) actionSpec.get(ADDITIONS));
                Set<Formula> deletions = readFrom((List<?>) actionSpec.get(DELETIONS));

                return Action.buildActionFrom(name, preconditions, additions, deletions, vars);


            } catch (Reader.ParsingException e) {
                e.printStackTrace();
                return null;
            }
        }).collect(Collectors.toSet());

        if (actions.stream().anyMatch(Objects::isNull)) {

            throw new Reader.ParsingException("Couldn't read actions: " + actionSpecs);
        }

        return actions;

    }

    private static Set<Formula> readFrom(List<?> objects) throws Reader.ParsingException {

        Set<Formula> formulae = objects.stream().map(x -> {
            try {
                return Reader.readFormula(x);
            } catch (Reader.ParsingException e) {
                e.printStackTrace();
                return null;
            }
        }).collect(Collectors.toSet());


        if (formulae.stream().anyMatch(Objects::isNull)) {

            throw new Reader.ParsingException("Couldn't read formulae: " + objects);
        }

        return formulae;


    }

    public Set<Formula> getBackground() {
        return background;
    }

    public Set<Action> getActions() {
        return actions;
    }

    public State getStart() {
        return start;
    }

    public State getGoal() {
        return goal;
    }

    public String getName() {
        return name;
    }

    public Optional<Set<List<Action>>> getExpectedActionSequencesOpt() {
        return expectedActionSequencesOpt;
    }

    public Map<String, Action> getActionMap() {
        return actionMap;
    }

    @Override
    public String toString() {
        return "PlanningProblem{" +
                "name='" + name + '\'' +
                ", background=" + background +
                ", actions=" + actions +
                ", start=" + start +
                ", goal=" + goal +
                '}';
    }
}
