package io.github.pixelsuite;
public final class DrawerPickerOrderTest {
 static int cmp(String a,String b){return DrawerOrder.comparePicker(a,a,b,b);}
 static void check(boolean yes){if(!yes)throw new AssertionError("Picker ordering regression");}
 public static void main(String[] args){
  check(cmp("Amazon","9GAG")<0);check(cmp("9GAG","Ωmega")<0);check(cmp("Ωmega","تطبيق")<0);
  check(cmp("2 Apps","10 Apps")<0);check(cmp("٢ Apps","10 Apps")<0);check(cmp("App 2","App 10")<0);
  check(DrawerOrder.comparePicker("Same","a","Same","b")<0);
  check(DrawerOrder.compare("تطبيق","a","9GAG","b")<0);
  String[] names={"Amazon","App 2","App 10","Ápp","9GAG","10 Apps","٢ Apps","@home","Ωmega","应用","تطبيق","واتساب","\u200fتطبيق","","😀"};
  int checks=8;
  for(String a:names)for(String b:names){check(Integer.signum(cmp(a,b))==-Integer.signum(cmp(b,a)));checks++;
   for(String c:names)if(cmp(a,b)<=0&&cmp(b,c)<=0){check(cmp(a,c)<=0);checks++;}}
  System.out.println("PASS: "+checks+" picker fixtures, antisymmetry/transitivity checks; drawer order preserved");
 }
}
